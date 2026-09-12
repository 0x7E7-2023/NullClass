(function () {
    // 重庆人文科技学院（强智 · 高校综合管理教务系统 /jsxsd/）课表解析
    // 移植自 shiguang_warehouse 的 CQRK/cqrk_01.js（MIT，上游作者 星河欲转）
    //   https://github.com/XingHeYuZhuan/shiguang_warehouse
    // 上游的核心是 parseTimetableToModel() + parseWeeks()：读 #kbtable 每个 td 里
    // div.kbcontent 的明细，课程名取第一个非空文本节点，font[title=教师|教室|周次(节次)]
    // 取教师/教室/周次，节次从周次串的方括号里抠（[01-02节]）。
    // 这里保持同一套字段来源，改动集中在六处（详见 AUDIT.md §7；本轮修复记录见 §10）：
    //   ① 星期按**网格列号**对齐（extract.js 每个格子都带 col，跨行/跨列的合并单元格也算得对）：
    //      课表首列是「节次」列，它常常 rowspan 跨两行，那一行的 td 会整体前移一格 ——
    //      按「第几个 td 就是星期几」算会把整行错一天。表头行用来认「星期 d 在第几列」，
    //      认不出时进 warnings；
    //   ② 周次按**逗号分段、各段自判单双**，单双写在数字前面（双周2-16）、写在「周」字
    //      后面（1-16周(双)）、被空白隔开（1-16周 双）都算数（上游 weekStr.split('(')[0]
    //      会把这些标记整串丢掉，变成每周都上）；
    //   ③ 「周」字写在括号里（1-16(周)）是**正常形态**（上游自己的注释就是
    //      '1-9,11-17(周)[01-02节]'）；括号里只有数字或数字区间的才是教学班序号
    //      （(1)、(1-2)），不当周次；
    //   ④ 节次除连字符外也认逗号写法（[09,10节]，上游 split('-') 只取到 09），并且
    //      **在解析阶段就夹住上限**（MAX_PERIOD 节）：越界的块按「读不出节次」处理 ——
    //      不夹的话占位作息会补出 24:00 这种非法时刻，整次导入会被载荷校验拒收；
    //   ⑤ 上游的 mergeAndDistinctCourses() 会按名字排序并把相邻节次合并成一整块，
    //      这里不合并（原样保留教务给的节次边界），排序由应用自己按节次做；
    //   ⑥ 上游静默丢掉的块（没课名 / 没周次 / 没节次 / 编号超范围）全部计数进 warnings，
    //      并且每条警告自带上限（载荷校验：≤20 条、每条 ≤200 字）—— 警告宁可截断，
    //      也不能让整次导出被拒收。
    //
    // 输入是 extract.js 交出来的原始结构：
    //   { source, pageUrl, now: "2026-09-12", term: { code, name }, cols: 8,
    //     rows: [ [ { col, text, parts: [原始 HTML] }, … ], … ] }
    // 这里不碰 DOM（CI 的 Rhino 里没有），全部按字符串 + 正则处理，是纯函数。
    var data = JSON.parse(__ncInput);
    var rows = data.rows || [];
    var term = data.term || {};

    // 学校作息时间（重庆人文科技学院全年统一作息，12 节）。上游硬编码在
    // saveAppTimeSlots() 里，逐条照搬。
    var PERIOD_TIMES = [
        { periodIndex: 1, start: '08:30', end: '09:15' },
        { periodIndex: 2, start: '09:20', end: '10:05' },
        { periodIndex: 3, start: '10:20', end: '11:05' },
        { periodIndex: 4, start: '11:10', end: '11:55' },
        { periodIndex: 5, start: '14:00', end: '14:45' },
        { periodIndex: 6, start: '14:50', end: '15:35' },
        { periodIndex: 7, start: '15:40', end: '16:25' },
        { periodIndex: 8, start: '16:40', end: '17:25' },
        { periodIndex: 9, start: '17:30', end: '18:15' },
        { periodIndex: 10, start: '19:00', end: '19:45' },
        { periodIndex: 11, start: '19:50', end: '20:35' },
        { periodIndex: 12, start: '20:40', end: '21:25' }
    ];
    var PERIOD_COUNT = PERIOD_TIMES.length;

    // 节次上限：越过它一定是脏数据（本批统一的判据 —— 合法作息不可能到 17 节以上）。
    // 这个数与上面那张表的「补占位」公式是一对：占位时刻写成 pad2(7 + 节次)，第 16 节 =
    // 23:00-23:45 是最后一个还在当天的档位，再往后补出来的就不是 00:00–23:59 的时刻了，
    // 而 JwPayloadCodec 的时间正则只收 HH:mm —— 那样的载荷整次导入都会被拒收。
    var MAX_PERIOD = 16;

    // 上游写死的学期总周数（教务页面不给这个值，只能照抄并进 warnings）
    var DEFAULT_TOTAL_WEEKS = 20;
    // 载荷校验的总周数上限（1..30），超出的周次按脏数据丢弃并进 warnings
    var MAX_WEEK = 30;
    // 载荷校验的上限：警告 ≤20 条、每条 ≤200 字（JwSchedulePayload.MAX_WARNINGS /
    // MAX_WARNING_TEXT。中文按 String.length 算，1 字 = 1）。
    var MAX_WARNINGS = 20;
    var MAX_WARNING_TEXT = 200;
    // 一个格子里放多门课时，教务用一长串减号分隔（上游写死 21/22 个，这里放宽到 5 个以上）
    var DASHES = /-{5,}/;
    var TEACHER_TITLE = /^(老师|教师|任课教师|授课教师|教师姓名)$/;
    var ROOM_TITLE = /^(教室|上课地点|上课教室|地点|教室名称)$/;
    var COURSE_TITLE = /^(课程|课程名称|课程名|科目)$/;
    var TEACHER_PREFIX = /^(任课教师|授课教师|教师|老师)\s*[:：]\s*/;
    var WEEK_BRACKET = /[\[【]\s*([^\]】]*)\s*[\]】]/;
    // 周次串里的括号：只有「周」字的一种是周字的正常写法（1-16(周)），
    // 只有数字/数字区间的一种是教学班序号（(1)、(1-2)），两种都不是周次。
    var WEEK_PAREN = /^[周週]$/;
    var SERIAL_PAREN = /^\d{1,3}(\s*[-—~至,，、]\s*\d{1,3})*$/;
    // 单双标记在周次串里可能被空白切成独立一段（"1-16周 双"），分段时按空白一并切
    var SEGMENT_SPLIT = /[\s,，、;；]+/;

    var droppedWeeks = 0;
    var skippedBlocks = 0;
    var noPeriodBlocks = 0;
    var overPeriodBlocks = 0;
    // 认不出单双的周次串（「单双」同段、只有「隔周」没有单双字）：不猜，按每周处理并出声
    var ambiguousMarks = 0;
    var ambiguousSamples = [];
    // 被跳过的课程块（最多记 5 条，点名进 warnings）—— 不许静默丢课
    var droppedNames = [];

    // 载荷里的核对提示。**一律经 pushWarning() 出去**：每条 ≤ MAX_WARNING_TEXT 字
    // （超了按码位截断加省略号）、一共 ≤ MAX_WARNINGS 条 —— 载荷校验对这两条是硬上限
    // （JwSchedulePayload 里也是 200/20），超一条整次导入就被拒收。警告是给用户看的，
    // 宁可短，也不能把载荷本身弄坏。
    var warnings = [];

    // 按码位截断：别把一个字符（代理对）劈成半个，末尾留一个省略号说明「这里还有内容」
    function clipText(text, max) {
        var one = String(text);
        if (one.length <= max) return one;
        var head = one.substring(0, max - 1);
        var last = head.charCodeAt(head.length - 1);
        if (last >= 0xD800 && last <= 0xDBFF) head = head.substring(0, head.length - 1);
        return head + '…';
    }

    function pushWarning(message) {
        if (warnings.length >= MAX_WARNINGS) return;
        var text = clipText(message, MAX_WARNING_TEXT);
        if (warnings.indexOf(text) >= 0) return;
        warnings.push(text);
    }

    function clean(value) {
        if (value === null || value === undefined) return '';
        return String(value).replace(/\s+/g, ' ').trim();
    }

    function pad2(n) {
        return (n < 10 ? '0' : '') + n;
    }

    function isoOf(date) {
        return date.getFullYear() + '-' + pad2(date.getMonth() + 1) + '-' + pad2(date.getDate());
    }

    // 提取时刻那一周的周一。§4.3：firstDay 是第 1 周的第一天，上游 firstDayOfWeek=1（周一），
    // 所以推算值取「回退到周一」的那天。不用 Date.parse：Rhino 对 ISO 串的支持不齐。
    function mondayOf(isoDate) {
        var m = /^(\d{4})-(\d{1,2})-(\d{1,2})/.exec(clean(isoDate));
        if (!m) return null;
        var day = new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]));
        var back = (day.getDay() + 6) % 7;
        return isoOf(new Date(day.getFullYear(), day.getMonth(), day.getDate() - back));
    }

    function stripTags(value) {
        if (value === null || value === undefined) return '';
        return String(value).replace(/<[^>]*>/g, '');
    }

    function decodeEntities(value) {
        return String(value)
            .replace(/&nbsp;/gi, ' ')
            .replace(/&lt;/gi, '<')
            .replace(/&gt;/gi, '>')
            .replace(/&quot;/gi, '"')
            .replace(/&#39;/g, '\'')
            .replace(/&amp;/gi, '&');
    }

    function plainText(value) {
        return clean(decodeEntities(stripTags(value)));
    }

    // 明细 HTML 里的 <font title="…">文本</font>，按出现顺序返回。
    // 末尾的 </font> 允许缺失（教务页面的 <font> 偶尔不闭合），那时取到下一个 '<' 为止。
    function fontsOf(blockHtml) {
        var html = String(blockHtml);
        var re = /<font\b[^>]*title\s*=\s*["']?([^"'>]+)["']?[^>]*>([\s\S]*?)(?:<\/font>|(?=<)|$)/gi;
        var out = [];
        var m = re.exec(html);
        while (m) {
            out.push({ title: clean(m[1]), text: plainText(m[2]) });
            m = re.exec(html);
        }
        return out;
    }

    // 课程名：明细里第一个 <font> 之前的第一行文字 —— 与上游「第一个非空文本节点」等价
    // （教务页面里课名就是 font 前面那段裸文本）；有的学校把课名也放进 font[title=课程]，
    // 补一个兜底。都没认出来就返回空串，调用方会把它计进 warnings（不静默丢）。
    function nameOf(blockHtml) {
        var lines = String(blockHtml).split(/<font\b/i)[0].split(/<br\s*\/?>/i);
        for (var i = 0; i < lines.length; i++) {
            var line = plainText(lines[i]);
            if (line) return line;
        }
        var fonts = fontsOf(blockHtml);
        for (var f = 0; f < fonts.length; f++) {
            if (COURSE_TITLE.test(fonts[f].title) && fonts[f].text) return fonts[f].text;
        }
        return '';
    }

    function titledText(fonts, pattern) {
        for (var i = 0; i < fonts.length; i++) {
            if (!pattern.test(fonts[i].title)) continue;
            var text = clean(fonts[i].text);
            if (text) return text;
        }
        return '';
    }

    // 上游把「任课教师:」前缀去掉，空值写「未知教师」/「未知地点」。
    // 我们的 teacher / location 允许为空，空着比写「未知」好（「未知」会当成真名显示）。
    function teacherOf(fonts) {
        var text = titledText(fonts, TEACHER_TITLE);
        if (!text) return null;
        text = clean(text.replace(TEACHER_PREFIX, ''));
        if (!text || text === '未知教师' || text === '未知') return null;
        return text;
    }

    function roomOf(fonts) {
        var text = titledText(fonts, ROOM_TITLE);
        if (!text || text === '未知地点' || text === '未知') return null;
        return text;
    }

    // font[title="周次(节次)"] 的文本，本平台形如 "1-16周[01-02节]"：
    //   周次 = 方括号**之前**那一段，节次 = 方括号里面那一小段。
    // 有的页面把括号写成全角【】，或者干脆没有方括号（整串都是周次），两种都认。
    function specOf(blockHtml) {
        var fonts = fontsOf(blockHtml);
        for (var i = 0; i < fonts.length; i++) {
            if (fonts[i].title.indexOf('周次') < 0) continue;
            var text = clean(fonts[i].text);
            if (!text) continue;
            var bracket = WEEK_BRACKET.exec(text);
            if (bracket && clean(bracket[1])) {
                return { weeks: clean(text.substring(0, bracket.index)), periods: clean(bracket[1]) };
            }
            return { weeks: text, periods: '' };
        }
        var only = titledText(fonts, /^节次$/);
        return only ? { weeks: '', periods: only } : null;
    }

    function pushWeek(out, week, oddOnly, evenOnly) {
        if (week < 1) return;
        if (week > MAX_WEEK) {
            droppedWeeks++;
            return;
        }
        if (oddOnly && week % 2 === 0) return;
        if (evenOnly && week % 2 === 1) return;
        out.push(week);
    }

    function uniqueSorted(weeks) {
        var seen = {};
        var out = [];
        for (var i = 0; i < weeks.length; i++) {
            var w = weeks[i];
            if (w >= 1 && !seen[w]) {
                seen[w] = true;
                out.push(w);
            }
        }
        out.sort(function (a, b) { return a - b; });
        return out;
    }

    // 周次串 → 周次数组。本批统一的写法口径（四种都要认）：
    //   1-16周 / 1-16周(双) / 1-15周(单)        单双写在「周」字后面
    //   1-16(周)                                 「周」字写在括号里 —— 上游 cqrk_01.js 的
    //                                            parseWeeks 注释就是这种形态（主要形态之一）
    //   双周2-16 / 2-16周 双                     单双写在数字**前面**、或被空白隔开（并列标记）
    //   (1)1-16周 / 9-10周(2)                    括号里的纯数字/数字区间是教学班序号，不是周次
    //   / 1-3,5周 / 第3周 / 1-8周(单),10-16周(双)
    //
    // 三条规矩（都与上游不同）：
    //   · **不按逗号 split 之后再数数字**：先分段，每段自己判单双 —— 上游整串 split('(')[0]，
    //     于是 "1-8周(单),10-16周" 后半段的单双被前半段带走（第一批真出过这个错）。
    //   · **括号里的纯数字不当周次**：单个数字与区间都算教学班序号（"(1)"、"(1-2)"），
    //     当成周次会把同一门课的真实周次一起读错；但 "(周)" 是周字的正常写法，不能否掉。
    //   · **认不出单双的一律出声**：「单双」写在同一段里、或只写「隔周」没有单双字 ——
    //     这些定不下来，按「每周都上」处理并进 warnings，不许静默改写。
    function weeksIn(source) {
        var text = clean(source);
        if (!text) return [];
        // 方括号/【】里的是节次（"1-16周[01-02节]"）：整段去掉，免得节次的数字被当成周次
        text = text.replace(/[\[【][^\]】]*[\]】]/g, ' ');
        // 括号逐个看：周字的正常写法与教学班序号整段抹掉，单双/其它写法留着（下面按段判）
        text = text.replace(/[（(]([^）)]*)[)）]/g, function (whole, inner) {
            var body = clean(inner);
            if (!body) return ' ';
            if (WEEK_PAREN.test(body)) return ' ';
            if (SERIAL_PAREN.test(body)) return ' ';
            return whole;
        });
        // 区间两端的空白吃掉（"1 - 16 周"）：按空白分段时，区间才不会被拆成两个周次
        text = text.replace(/(\d)\s*[-—~至到]\s*(\d)/g, '$1-$2');
        var segments = text.split(SEGMENT_SPLIT);
        // 被空白切成独立一段的单/双（"1-16周 双"）并给最近的周次段：并列标记不许丢。
        // 标在数字**前面**的（"双周2-16"）本来就在同一段里，不用并。
        for (var i = 0; i < segments.length; i++) {
            var seg = segments[i];
            if (!seg || /\d/.test(seg)) continue;
            if (seg.indexOf('单') < 0 && seg.indexOf('双') < 0) continue;
            var into = -1;
            for (var back = i - 1; back >= 0; back--) {
                if (/\d/.test(segments[back])) { into = back; break; }
            }
            for (var fwd = i + 1; into < 0 && fwd < segments.length; fwd++) {
                if (/\d/.test(segments[fwd])) { into = fwd; break; }
            }
            if (into < 0) continue;
            segments[into] = segments[into] + seg;
            segments[i] = '';
        }
        var weeks = [];
        for (var s = 0; s < segments.length; s++) {
            var segment = segments[s];
            if (!segment || !/\d/.test(segment)) continue;
            var oddOnly = segment.indexOf('单') >= 0;
            var evenOnly = segment.indexOf('双') >= 0;
            if (oddOnly && evenOnly) {
                // 「单双周」两个标记同时出现：认不出到底上哪几周（按每周处理并出声）
                noteAmbiguous(segment);
                oddOnly = false;
                evenOnly = false;
            } else if (!oddOnly && !evenOnly && segment.indexOf('隔') >= 0) {
                // 「隔周」要有上一周做基准才定得下来，光看这一段定不了
                noteAmbiguous(segment);
            }
            var range = /(\d{1,2})\s*[-—~至]\s*(\d{1,2})/.exec(segment);
            if (range) {
                var start = parseInt(range[1], 10);
                var end = parseInt(range[2], 10);
                for (var week = start; week <= end; week++) pushWeek(weeks, week, oddOnly, evenOnly);
                continue;
            }
            var single = /(\d{1,2})/.exec(segment);
            if (single) pushWeek(weeks, parseInt(single[1], 10), oddOnly, evenOnly);
        }
        return uniqueSorted(weeks);
    }

    // 认不出单双的周次串：记样本（最多 3 条）与总数，最后进 warnings
    function noteAmbiguous(segment) {
        ambiguousMarks++;
        var text = clean(segment);
        if (!text || ambiguousSamples.indexOf(text) >= 0) return;
        if (ambiguousSamples.length >= 3) return;
        ambiguousSamples.push(text);
    }

    // 节次：方括号里的内容。"01-02节" / "1-2节" / "0102节" / "09,10节" / "第9,10节" 都认；
    // 上游用 split('-') 只取首尾两个数，逗号写法（[09,10节]）会只剩一个数，这里按分隔符全切。
    // 连堂也写成 "030405节"（两位一节），按两位切。
    // 这里只负责读出 min/max，**不判上限** —— 上限（MAX_PERIOD）由调用方判，因为超限的块
    // 要按「读不出节次」跳过并点名（读出来是 78 节的脏数据要能说清是哪一门课）。
    function periodsIn(source) {
        var body = clean(source).replace(/[第节\s]/g, '').replace(/[（）()]/g, '');
        if (!body) return null;
        var start = null;
        var end = null;
        function note(value) {
            if (!(value >= 1)) return;
            if (start === null || value < start) start = value;
            if (end === null || value > end) end = value;
        }
        var segments = body.split(/[,，]/);
        for (var i = 0; i < segments.length; i++) {
            var segment = segments[i];
            var range = /^(\d{1,2})\s*[-—~至]\s*(\d{1,2})$/.exec(segment);
            if (range) {
                note(parseInt(range[1], 10));
                note(parseInt(range[2], 10));
                continue;
            }
            if (!/^\d+$/.test(segment)) return null;
            if (segment.length >= 4 && segment.length % 2 === 0) {
                for (var k = 0; k < segment.length; k += 2) note(parseInt(segment.substring(k, k + 2), 10));
            } else {
                note(parseInt(segment, 10));
            }
        }
        if (start === null || end === null) return null;
        return { start: start, end: end };
    }

    // 周次集合 → 极大段（§4.1）：步长 1 视作每周，步长 2 视作单/双周
    function runsOf(weeks) {
        var runs = [];
        var i = 0;
        while (i < weeks.length) {
            var step = 1;
            if (i + 1 < weeks.length && weeks[i + 1] - weeks[i] === 2) step = 2;
            var j = i;
            while (j + 1 < weeks.length && weeks[j + 1] - weeks[j] === step) j++;
            var run = { start: weeks[i], end: weeks[j] };
            if (run.start === run.end || step === 1) {
                run.weekType = 'ALL';
            } else {
                run.weekType = run.start % 2 === 1 ? 'ODD' : 'EVEN';
            }
            runs.push(run);
            i = j + 1;
        }
        return runs;
    }

    // 表头行也参与列号计数（上游一样），但它没有课，直接跳过。
    // 「表头」= 没有任何课程格、且能认出 ≥4 个星期标签的那一行。
    // 认出来的星期标签超过 7 个 = 表头里星期方向真的伸进了第 8 列（课表被拉宽了），
    // 这时只按「第 N 列 = 星期 N」取前 7 列并进 warnings —— 否则 8 列表头（节次 + 周一至周日）
    // 是强智的正常形状，不该报。
    var DAY_CHARS = '一二三四五六日天';

    // 「2026-2027-1」→「2026-2027学年第一学期」（教务不给学期名时用它）
    function termNameFromCode(code) {
        var m = /^(\d{4})-(\d{4})-(\d)$/.exec(clean(code));
        if (!m) return '';
        if (m[3] === '1') return m[1] + '-' + m[2] + '学年第一学期';
        if (m[3] === '2') return m[1] + '-' + m[2] + '学年第二学期';
        if (m[3] === '3') return m[1] + '-' + m[2] + '学年第三学期';
        return m[1] + '-' + m[2] + '学年第' + m[3] + '学期';
    }

    function dayOfLabel(value) {
        var m = /(?:星期|周|礼拜)\s*([一二三四五六日天1-7])/.exec(clean(value));
        if (!m) return 0;
        var at = DAY_CHARS.indexOf(m[1]);
        if (at >= 0) return m[1] === '天' ? 7 : at + 1;
        return parseInt(m[1], 10);
    }

    // 表头行：找「没有课程内容、且能认出 ≥4 个星期标签」的那一行，记下每个星期在第几列。
    // 列号用格子自带 col（extract.js 交出来的网格列号），没有 col 的旧 fixture / 手写输入
    // 退回它在数组里的下标 —— 这两种在「首列是节次列」的表里差一格，正是要修的那处。
    // 两本账分开记：headerLabelCells 是**带星期标签的格子数**（同一天出现两次也算两个），
    // headerLabels 是**认出来的不同天数**（最多 7）—— 前者用来报「表被拉宽了」，
    // 后者用来报「有几天没认出来」。
    var dayCol = [0, -1, -1, -1, -1, -1, -1, -1];
    var headerLabelCells = 0;

    function colOfCell(cell, index) {
        var col = cell ? cell.col : undefined;
        return (typeof col === 'number' && col >= 0) ? col : index;
    }

    function headerRowOf() {
        var best = -1;
        var bestLabels = 0;
        var bestCells = 0;
        for (var r = 0; r < rows.length; r++) {
            var hasContent = false;
            var labels = 0;
            var cells = 0;
            var cols = [0, -1, -1, -1, -1, -1, -1, -1];
            for (var c = 0; c < rows[r].length; c++) {
                if (rows[r][c].parts && rows[r][c].parts.length) hasContent = true;
                var day = dayOfLabel(rows[r][c].text);
                if (day < 1 || day > 7) continue;
                cells++;
                if (cols[day] >= 0) continue;
                cols[day] = colOfCell(rows[r][c], c);
                labels++;
            }
            if (hasContent || labels < 4) continue;
            if (labels > bestLabels) {
                bestLabels = labels;
                bestCells = cells;
                best = r;
                dayCol = cols;
            }
        }
        headerLabels = bestLabels;
        headerLabelCells = bestCells;
        return best;
    }

    var headerLabels = 0;
    var headerRow = headerRowOf();

    // 表宽（网格列数）：extract.js 交出来的 cols 最准；没有就按所有格子里最大的列号推。
    // 只在「表头没认全、要按列号推断」时用得上。
    var tableCols = Number(data.cols) || 0;
    if (!(tableCols > 0)) {
        for (var tr = 0; tr < rows.length; tr++) {
            for (var tc = 0; tc < rows[tr].length; tc++) {
                var at = colOfCell(rows[tr][tc], tc) + 1;
                if (at > tableCols) tableCols = at;
            }
        }
    }

    // 表头里认出来的第一个星期标签决定的「列偏移」：强智的表首列是节次列（偏移 1），
    // 没有节次列的表偏移 0。表头只认出一部分时要靠它把没认出来的那几天放回正确的列。
    var dayOffset = -1;
    for (var od = 1; od <= 7; od++) {
        if (dayCol[od] >= 0) {
            dayOffset = dayCol[od] - (od - 1);
            break;
        }
    }

    // 星期 d 在哪一列（网格列号）：
    //   ① 表头里认出了这个星期标签 → 用表头自己给的列号（最稳）；
    //   ② 表头认出一部分 → 按已认出的偏移推断（推断出来的列不能越界、也不能和别的天撞车，
    //      撞了就不猜 —— 宁可少认一天，也不把课排到错的星期上）；
    //   ③ 表头一个标签都认不出 → 返回 -1，调用方走「第 1 列 = 星期一」的兜底并进 warnings。
    function dayColumnOf(day) {
        if (dayCol[day] >= 0) return dayCol[day];
        if (dayOffset < 0) return -1;
        var guess = dayOffset + day - 1;
        if (tableCols > 0 && guess >= tableCols) return -1;
        for (var k = 1; k <= 7; k++) {
            if (k !== day && dayCol[k] === guess) return -1;
        }
        return guess;
    }

    // 网格列号 → 星期几。表头认出过标签时按表头给的列号找；一个标签都认不出时按
    // 「第 1 列 = 星期一」兜底（与 warnings 里说明的一致）。
    function dayOfColumn(col) {
        for (var d = 1; d <= 7; d++) {
            if (dayColumnOf(d) === col) return d;
        }
        if (headerLabels === 0 && col >= 0 && col < 7) return col + 1;
        return 0;
    }

    // 「课程块」的身份证：没有它就没法告诉用户哪一门课被跳过了
    function noteDropped(name, teacher, reason) {
        if (droppedNames.length >= 5) return;
        var text = name || '(没认出课名)';
        if (teacher) text = text + '（' + teacher + '）';
        droppedNames.push(text + '——' + reason);
    }

    function droppedText() {
        if (!droppedNames.length) return '';
        return '包含：' + droppedNames.join('；');
    }

    var order = [];
    var byCourse = {};

    for (var ri = 0; ri < rows.length; ri++) {
        if (ri === headerRow) continue;
        var row = rows[ri];
        // 逐个格子按**它自己的列号**定位星期 —— 不按数组下标：强智的首列是节次列，
        // 被 rowspan 跨掉的那一行里第一个 td 是 col 1（星期一那格），按下标算会整行错一天。
        for (var ci = 0; ci < row.length; ci++) {
            var cell = row[ci];
            var d = dayOfColumn(colOfCell(cell, ci));
            if (d < 1) continue;
            var parts = cell.parts || [];
            for (var p = 0; p < parts.length; p++) {
                var blocks = String(parts[p]).split(DASHES);
                for (var b = 0; b < blocks.length; b++) {
                    var blockHtml = blocks[b];
                    if (plainText(blockHtml) === '') continue;
                    var fonts = fontsOf(blockHtml);
                    var name = nameOf(blockHtml);
                    var teacher = teacherOf(fonts);
                    if (!name) {
                        skippedBlocks++;
                        continue;
                    }
                    var spec = specOf(blockHtml);
                    var periods = spec && spec.periods ? periodsIn(spec.periods) : null;
                    if (!periods) {
                        // 认不出节次就不知道这门课放哪几节 —— 只能跳过，但必须报出来（不许静默丢课）
                        noPeriodBlocks++;
                        noteDropped(name, teacher, '没认出节次');
                        continue;
                    }
                    if (periods.end > MAX_PERIOD) {
                        // 节次越过上限（[17-18节]、或被切成 12/34/56/78 的脏数据）：同样按
                        // 「读不出节次」处理。**不能**照单收下 —— 占位作息会补出 24:00 这种
                        // 非法时刻，载荷校验一拒，整次导入就白导了。
                        overPeriodBlocks++;
                        noteDropped(name, teacher, '节次超出 ' + MAX_PERIOD + ' 节');
                        continue;
                    }
                    var weeks = spec ? weeksIn(spec.weeks) : [];
                    if (!weeks.length) {
                        skippedBlocks++;
                        noteDropped(name, teacher, '没认出周次');
                        continue;
                    }
                    var key = name + ' ' + (teacher || '');
                    var course = null;
                    var runs = runsOf(weeks);
                    for (var n = 0; n < runs.length; n++) {
                        var run = runs[n];
                        // 课程要等真产出一条安排才登记：不能在课表里留下一门「没有任何安排」的空课
                        if (!course) {
                            if (!byCourse[key]) {
                                byCourse[key] = {
                                    name: name,
                                    teacher: teacher,
                                    note: null,
                                    blocks: [],
                                    seen: {}
                                };
                                order.push(key);
                            }
                            course = byCourse[key];
                        }
                        var block = {
                            dayOfWeek: d,
                            startPeriod: periods.start,
                            endPeriod: periods.end,
                            startWeek: run.start,
                            endWeek: run.end,
                            weekType: run.weekType,
                            location: roomOf(fonts)
                        };
                        var blockKey = [block.dayOfWeek, block.startPeriod, block.endPeriod,
                            block.startWeek, block.endWeek, block.weekType,
                            block.location || ''].join('|');
                        if (course.seen[blockKey]) continue;
                        course.seen[blockKey] = true;
                        course.blocks.push(block);
                    }
                }
            }
        }
    }

    if (order.length === 0) {
        // 一个块都没解析出来：要说清是「没课」还是「课都在解析时被跳过了」——
        // 后者是教务页面换了写法或数据有脏（比如节次超出 16 节），不是没排课。
        var totalSkipped = skippedBlocks + noPeriodBlocks + overPeriodBlocks;
        if (totalSkipped > 0) {
            throw new Error(clipText('本学期没有解析到任何课程：课表里的 ' + totalSkipped +
                ' 个课程块都没能解析出来（周次/节次写法不认识，或节次超出 ' + MAX_PERIOD +
                ' 节）。' + droppedText() + ' 请把教务页面的课表截图反馈给适配器作者', MAX_WARNING_TEXT));
        }
        throw new Error(
            '本学期没有解析到任何课程：可能是还没排课，或教务页面改了结构（也可能登录状态已失效）'
        );
    }

    var maxWeek = 0;
    var maxPeriod = 0;
    var courses = [];
    for (var oi = 0; oi < order.length; oi++) {
        var item = byCourse[order[oi]];
        for (var bi = 0; bi < item.blocks.length; bi++) {
            if (item.blocks[bi].endWeek > maxWeek) maxWeek = item.blocks[bi].endWeek;
            if (item.blocks[bi].endPeriod > maxPeriod) maxPeriod = item.blocks[bi].endPeriod;
        }
        courses.push({
            name: item.name,
            teacher: item.teacher,
            note: item.note,
            blocks: item.blocks
        });
    }

    var totalWeeks = maxWeek > DEFAULT_TOTAL_WEEKS ? maxWeek : DEFAULT_TOTAL_WEEKS;
    if (totalWeeks > MAX_WEEK) totalWeeks = MAX_WEEK;

    // 作息表：内置 12 节盖住上课用的节次。课表里出现 >12 节（教务临时加的时段）时补出
    // 占位条目 —— 不补的话那些课会画到课表外面去（见 warnings 里的说明）。
    // 占位时刻写成 pad2(7 + 节次)：节次在解析阶段已夹进 MAX_PERIOD，所以补出来的时刻一定
    // 落在 08:00–23:45（合法 HH:mm、不跨天）—— 这两处的耦合写在 MAX_PERIOD 的注释里。
    var periodTimes = [];
    for (var pi = 0; pi < PERIOD_TIMES.length; pi++) {
        periodTimes.push({
            periodIndex: PERIOD_TIMES[pi].periodIndex,
            start: PERIOD_TIMES[pi].start,
            end: PERIOD_TIMES[pi].end
        });
    }
    for (var extra = PERIOD_COUNT + 1; extra <= maxPeriod; extra++) {
        periodTimes.push({
            periodIndex: extra,
            start: pad2(7 + extra) + ':00',
            end: pad2(7 + extra) + ':45'
        });
    }

    var termName = clean(term.name) || termNameFromCode(term.code);

    // extract.js 一定会带 now（提取时刻）；缺了就没法推算开学日，明确报错而不是瞎猜一个日期
    var firstDay = mondayOf(data.now);
    if (!firstDay) {
        throw new Error('提取数据里缺少 now（提取时刻），无法推算开学日期');
    }

    // 推算/假定出来的东西逐条说清楚（§4.2）：这些值在库里和真值长得一模一样，
    // 不说明用户就没有机会发现「现在第几周」是错的。
    pushWarning('开学日期无法从教务获取，已按最近的周一（' + firstDay + '）推算，请在「学期管理」里核对');
    pushWarning('教务页面不提供学期总周数与作息时间：总周数按 20 周起算、节次时间按适配器内置的 12 节设定，请在「学期管理」里核对');
    if (maxWeek > DEFAULT_TOTAL_WEEKS) {
        pushWarning('课表里有到第 ' + maxWeek + ' 周的课，超过了内置的 ' + DEFAULT_TOTAL_WEEKS +
            ' 周，学期总周数已按 ' + totalWeeks + ' 周计，请在「学期管理」里核对');
    }
    if (maxPeriod > PERIOD_COUNT) {
        pushWarning('课表里出现了内置作息表没有的第 ' + (PERIOD_COUNT + 1) + '-' + maxPeriod +
            ' 节，已补上占位时间（' + periodTimes[PERIOD_COUNT].start + ' 起），请到「学期管理」里核对真实上下课时间');
    }
    if (droppedWeeks > 0) {
        pushWarning('有 ' + droppedWeeks + ' 条周次超出 ' + MAX_WEEK + ' 周，已按脏数据丢弃');
    }
    if (skippedBlocks > 0 || noPeriodBlocks > 0 || overPeriodBlocks > 0) {
        var parts2 = [];
        if (skippedBlocks > 0) parts2.push(skippedBlocks + ' 个没认出周次');
        if (noPeriodBlocks > 0) parts2.push(noPeriodBlocks + ' 个没认出节次');
        if (overPeriodBlocks > 0) parts2.push(overPeriodBlocks + ' 个节次超出 ' + MAX_PERIOD + ' 节');
        // 被跳过的块按名字点名（最多 5 个）—— 名单可能很长，pushWarning 会把它截到 200 字以内
        pushWarning('有 ' + parts2.join('、') + ' 的课程块已跳过（教务页面结构可能已调整，欢迎反馈）。' +
            droppedText());
    }
    if (ambiguousMarks > 0) {
        var marks = ambiguousSamples.length ? '（' + ambiguousSamples.join('、') + '）' : '';
        pushWarning('有 ' + ambiguousMarks + ' 处周次里的单双/隔周标记认不出来' + marks +
            '，这些周次已按「每周都上」处理，请核对导入结果');
    }
    if (headerLabels === 0) {
        pushWarning('课表里没找到星期表头，也认不出星期所在的列：已按「第 1 列 = 星期一」对齐，' +
            '课表可能整体错位，请核对导入结果');
    } else {
        if (headerLabels < 7) {
            pushWarning('星期表头只认出了 ' + headerLabels + ' 天，没认出来的那几天按列偏移推断，请核对导入结果');
        }
        if (headerLabelCells > 7) {
            pushWarning('表头里出现了 ' + headerLabelCells +
                ' 个星期标签，比星期一到星期日多：同一天以第一次出现的列为准，请核对导入结果');
        }
    }
    if (!termName) {
        termName = '重庆人文科技学院课表';
        pushWarning('没能识别出学年学期名称，学期名已用「重庆人文科技学院课表」占位，请在「学期管理」里改名');
    }

    return JSON.stringify({
        specVersion: 1,
        kind: 'schedule',
        ocrAssisted: false,
        warnings: warnings,
        terms: [
            {
                name: termName,
                firstDay: firstDay,
                totalWeeks: totalWeeks,
                periodTimes: periodTimes,
                courses: courses
            }
        ]
    });
})()
