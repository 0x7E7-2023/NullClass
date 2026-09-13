(function () {
    // 南昌航空大学科技学院（强智 · 高校综合管理教务系统 /jsxsd/）课表解析
    // 移植自 shiguang_warehouse 的 STCNCHU/stcnchu.js（MIT，上游作者 星河欲转）
    //   https://github.com/XingHeYuZhuan/shiguang_warehouse
    // 上游的核心是 parseTimetableToModel() + parseWeeks()：读 #kbtable 每个 td 里
    // div.kbcontent 的明细，课程名取第一个非空文本节点，font[title=老师|教师|教室|周次(节次)]
    // 取教师/教室/周次，节次从周次串的方括号里抠（[01-02节]），一段时间内相邻节次合并成一整块。
    // 这里保持同一套字段来源，改动集中在十处（详见 AUDIT.md §7）：
    //   ① 星期按**网格列号**对齐（extract.js 每个格子都带 col/span，跨行/跨列的合并单元格
    //      也算得对）：课表首列是「节次」列，它常常 rowspan 跨两行，那一行的 td 会整体前移
    //      一格 —— 上游「第几个 td 就是星期几」会把整行错一天（更糟的是整门课读不出星期而被丢）；
    //   ② 周次按**逗号分段、各段自判单双**，单双写在「周」字后面（1-16周(单)）、写在数字
    //      前面（(双)2-16周）、写在括号里带「周」字（1-16(单周)）都算数。上游
    //      weekStr.split('(')[0] 把括号连同里面的单双整串丢掉 —— 本平台的主要形态就是
    //      "1-9,11-17(周)[01-02节]"，单双版 "1-16(单周)[01-02节]" 会被它读成「每周都上」；
    //      而 "1-16周(单)" 这种写法更糟：split 之后剩 "1-16周"，Number("16周") 是 NaN，
    //      整门课的周次直接变成空数组（静默丢周次）；
    //   ③ 括号里只有数字或数字区间的（(1)、(1-2)）是**教学班序号**，不是周次；
    //      只有「周」字的（(周)）才是周字的正常写法；
    //   ④ 节次除连字符外也认逗号写法（[09,10节]，上游 split('-') 只取到 09），并且
    //      **在解析阶段就夹住上限**（MAX_PERIOD 节）：越界的块按「读不出节次」处理 ——
    //      不夹的话占位作息会补出 24:00 这种非法时刻，整次导入会被载荷校验拒收；
    //   ⑤ 上游的 mergeAndDistinctCourses() 会按名字排序并把相邻节次合并成一整块，
    //      这里不合并（原样保留教务给的节次边界），排序由应用自己按节次做；
    //   ⑥ 上游静默丢掉的块（没课名 / 没周次 / 没节次 / 编号超范围）全部计数进 warnings，
    //      并且每条警告自带上限（载荷校验：≤20 条、每条 ≤200 字）—— 警告宁可截断，
    //      也不能让整次导出被拒收；
    //   ⑦ 作息表按**学期**选：第一学期用共青城校区冬令时、第二学期用夏令时（上游让用户
    //      在弹窗里选校区与学期）；校区本身取不到，取不到的一律进 warnings 让用户核对；
    //   ⑧ 「周次(节次)」这个 font 在页面上就是本平台的形态，方括号【】与全角括号也一并认；
    //   ⑨ 一个块里写了**几段**周次就产出几段（两个 font，或一个 font 里连着写
    //      "1-8周[01-02节]10-16周[03-04节]"）—— 上游与旧实现只看第一段，第二段静默消失；
    //   ⑩ 周次的区间分隔符**先归一**再取区间：全角波浪 ～、全角减号 －、数学减号 −、
    //      短破折 –、长破折 —、「至/到」都当连字符 —— 不收的话 "1～16周" 会掉到单数字
    //      回退，静默变成「只上第 1 周」；标准形态 "1-16周" 结果不变。
    //
    // 输入是 extract.js 交出来的原始结构：
    //   { source, pageUrl, now: "2026-09-13", term: { code, name }, cols: 8,
    //     rows: [ [ { col, span, text, parts: [原始 HTML] }, … ], … ] }
    // 这里不碰 DOM（CI 的 Rhino 里没有），全部按字符串 + 正则处理，是纯函数。
    var data = JSON.parse(__ncInput);
    var rows = data.rows || [];
    var term = data.term || {};

    // 学校作息时间（上游硬编码在 saveAppTimeSlots() 里，逐条照搬，只把「第一/第二学期」
    // 换成本平台能读到的学期号）。两套校区作息都是 12 节。
    //   共青城校区：冬令时（第一学期）/ 夏令时（第二学期）—— 只有第 5-8 节不同
    //   上海路校区：全年一套
    var GONGQINGCHENG_WINTER = [
        { periodIndex: 1, start: '08:30', end: '09:10' },
        { periodIndex: 2, start: '09:15', end: '09:55' },
        { periodIndex: 3, start: '10:05', end: '10:45' },
        { periodIndex: 4, start: '10:50', end: '11:30' },
        { periodIndex: 5, start: '13:20', end: '14:00' },
        { periodIndex: 6, start: '14:05', end: '14:45' },
        { periodIndex: 7, start: '14:55', end: '15:35' },
        { periodIndex: 8, start: '15:40', end: '16:20' },
        { periodIndex: 9, start: '19:00', end: '19:40' },
        { periodIndex: 10, start: '19:45', end: '20:25' },
        { periodIndex: 11, start: '20:30', end: '21:10' },
        { periodIndex: 12, start: '21:15', end: '21:55' }
    ];
    var GONGQINGCHENG_SUMMER = [
        { periodIndex: 1, start: '08:30', end: '09:10' },
        { periodIndex: 2, start: '09:15', end: '09:55' },
        { periodIndex: 3, start: '10:05', end: '10:45' },
        { periodIndex: 4, start: '10:50', end: '11:30' },
        { periodIndex: 5, start: '13:40', end: '14:20' },
        { periodIndex: 6, start: '14:25', end: '15:05' },
        { periodIndex: 7, start: '15:15', end: '15:55' },
        { periodIndex: 8, start: '16:00', end: '16:40' },
        { periodIndex: 9, start: '19:00', end: '19:40' },
        { periodIndex: 10, start: '19:45', end: '20:25' },
        { periodIndex: 11, start: '20:30', end: '21:10' },
        { periodIndex: 12, start: '21:15', end: '21:55' }
    ];
    var SHANGHAI_ROAD = [
        { periodIndex: 1, start: '08:00', end: '08:45' },
        { periodIndex: 2, start: '08:55', end: '09:40' },
        { periodIndex: 3, start: '10:00', end: '10:45' },
        { periodIndex: 4, start: '10:55', end: '11:40' },
        { periodIndex: 5, start: '14:00', end: '14:45' },
        { periodIndex: 6, start: '14:55', end: '15:40' },
        { periodIndex: 7, start: '16:00', end: '16:45' },
        { periodIndex: 8, start: '16:55', end: '17:40' },
        { periodIndex: 9, start: '19:00', end: '19:45' },
        { periodIndex: 10, start: '19:55', end: '20:40' },
        { periodIndex: 11, start: '20:50', end: '21:35' },
        { periodIndex: 12, start: '21:45', end: '22:30' }
    ];
    var PERIOD_COUNT = GONGQINGCHENG_WINTER.length;

    // 节次上限：越过它一定是脏数据（合法作息不可能到 17 节以上）。这个数与下面「补占位」
    // 的公式是一对：占位时刻写成 pad2(7 + 节次)，第 16 节 = 23:00-23:45 是最后一个还在
    // 当天的档位 —— 再往后补出来的就不是 00:00–23:59 的时刻了，而载荷校验的时间只收
    // HH:mm，那样的载荷整次导入都会被拒收。
    var MAX_PERIOD = 16;

    // 上游写死的学期总周数（saveAppConfig 里的 semesterTotalWeeks，教务页面不给这个值）
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
    // 本平台「周次(节次)」的文本形如 "1-9,11-17(周)[01-02节]"：方括号里是节次，
    // 前面那一段才是周次。方括号的全角【】也认。切分在 splitSpec() 里。
    // 周次串里的括号：只有「周」字的一种是周字的正常写法（1-16(周)），
    // 只有数字/数字区间的一种是教学班序号（(1)、(1-2)），两种都不是周次。
    var WEEK_PAREN = /^[周週]$/;
    var SERIAL_PAREN = /^\d{1,3}(\s*[-—~至,，、]\s*\d{1,3})*$/;
    // 单双标记也可能被空白切成独立一段（"1-16周 双"），分段时按空白一并切
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
    var mergedCells = 0;

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
    // （教务页面里课名就是 font 前面那段裸文本）；有的页面把课名也放进 font[title=课程]，
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

    // font[title="周次(节次)"] 的文本，本平台形如 "1-9,11-17(周)[01-02节]"：
    //   周次 = 方括号**之前**那一段，节次 = 方括号里面那一小段。
    // 有的页面把括号写成全角【】，或者干脆没有方括号（整串都是周次），两种都认。
    // **一个 font 里可以连着写多段**（"1-8周[01-02节]10-16周[03-04节]"）：按方括号一段段切出来，
    // 每段各自成一份 {weeks, periods} —— 之前只看第一个方括号，第二段整段静默消失。
    function splitSpec(text) {
        var body = clean(text);
        var out = [];
        var re = /[\[【]\s*([^\]】]*)\s*[\]】]/g;
        var last = 0;
        var m = re.exec(body);
        while (m) {
            out.push({ weeks: clean(body.substring(last, m.index)), periods: clean(m[1]) });
            last = m.index + m[0].length;
            m = re.exec(body);
        }
        // 最后一个方括号**之后**还剩东西时：只有含数字的才当成又一段（"1-16周" 这种没有方括号、
        // 整串都是周次的写法也走这里）；纯文字/标点的尾巴丢掉，免得凭空多出一条「没认出节次」
        var tail = clean(body.substring(last));
        if (tail && /\d/.test(tail)) out.push({ weeks: tail, periods: '' });
        if (!out.length) out.push({ weeks: body, periods: '' });
        return out;
    }

    // 一个课程块里的「周次」字段：本平台通常只写一段，但同一个块里写两段也出现过
    //（两个 font，或一个 font 里连着写）—— **挨个读出来，都产出**，不许命中第一段就 return。
    function specsOf(blockHtml) {
        var fonts = fontsOf(blockHtml);
        var out = [];
        var onlyPeriods = '';
        for (var i = 0; i < fonts.length; i++) {
            var title = fonts[i].title;
            var text = clean(fonts[i].text);
            if (!text) continue;
            if (title.indexOf('周次') < 0) {
                if (/^节次$/.test(title) && !onlyPeriods) onlyPeriods = text;
                continue;
            }
            var parts = splitSpec(text);
            for (var k = 0; k < parts.length; k++) out.push(parts[k]);
        }
        // 只有「节次」标题的那种 font（节次的独立写法）：与原实现一样，**只在整块没有
        //「周次」字段时**才用它单独成一段（有「周次」字段时节次一律取自它自己的方括号）；
        // 整块两个字段都没有则返回空数组，调用方按「没认出节次」跳过并点名。
        if (!out.length) {
            return onlyPeriods ? [{ weeks: '', periods: onlyPeriods }] : [];
        }
        return out;
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

    // 认不出单双的周次串：记样本（最多 3 条）与总数，最后进 warnings
    function noteAmbiguous(segment) {
        ambiguousMarks++;
        var text = clean(segment);
        if (!text || ambiguousSamples.indexOf(text) >= 0) return;
        if (ambiguousSamples.length >= 3) return;
        ambiguousSamples.push(text);
    }

    // 周次串 → 周次数组。四种写法都要认（本平台四种都出现过，上游一种都没认全）：
    //   1-9,11-17(周)[01-02节]   本平台的主要形态，「周」字写在括号里
    //   1-16周(单) / 1-16周(双)  单双写在「周」字后面 —— 上游在这里连周次都读空（Number("16周")）
    //   (单)1-16周 / (双)2-16周  单双写在数字**前面**
    //   1-16(单周)               单双与「周」字一起写在括号里（本平台的单双版）
    //   1-3,5-9周                逗号分段混排，段与段**各自**判单双
    //   (1)1-16周 / 1-8周(2)     括号里的纯数字/数字区间是教学班序号，不是周次
    //
    // 三条规矩（都与上游不同）：
    //   · **不按 '(' 一刀切**：上游 weekStr.split('(')[0] 会把括号里的单双、以及括号后面的
    //     所有内容一起丢掉（"1-8周(单),10-16周" 的后半段也被带走）。
    //   · **括号里的纯数字不当周次**：单个数字与区间都算教学班序号（"(1)"、"(1-2)"），
    //     当成周次会把同一门课的真实周次一起读错；但 "(周)" 是周字的正常写法，不能否掉。
    //   · **认不出单双的一律出声**：「单双」写在同一段里、或只写「隔周」没有单双字 ——
    //     这些定不下来，按「每周都上」处理并进 warnings，不许静默改写。
    function weeksIn(source) {
        var text = clean(source);
        if (!text) return [];
        // 区间分隔符**先归一**：全角波浪 ～（U+FF5E）、全角减号 －（U+FF0D）、数学减号 −（U+2212）、
        // 短破折 –（U+2013）、长破折 —（U+2014）、半角 ~、以及「至/到」都当连字符。
        // 不归一的话 "1～16周" 落不进下面的区间正则，会掉到「单数字回退」——
        // 静默变成只上第 1 周（16 周丢 15 周），而且一条告警都没有。
        text = text.replace(/[～－−–—~至到]/g, '-');
        // 方括号/【】里的是节次（"1-16(周)[01-02节]"）：整段去掉，免得节次的数字被当成周次
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
        // （分隔符上面已经归一成半角连字符了）
        text = text.replace(/(\d)\s*-\s*(\d)/g, '$1-$2');
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
            // 只有一个区间分隔符形态（上面已归一）：1-16 / 1 - 16
            var range = /(\d{1,2})\s*-\s*(\d{1,2})/.exec(segment);
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

    // 节次：方括号里的内容。"01-02节" / "1-2节" / "0102节" / "09,10节" / "第9,10节" 都认；
    // 上游用 split('-') 只取首尾两个数，逗号写法（[09,10节]）会只剩一个数，这里按分隔符全切。
    // 连堂也写成 "030405节"（两位一节），按两位切。
    // 这里只负责读出 min/max，**不判上限** —— 上限（MAX_PERIOD）由调用方判，因为超限的块
    // 要按「读不出节次」跳过并点名（读出来是 78 节的脏数据要能说清是哪一门课）。
    function periodsIn(source) {
        // 与 weeksIn 同一套分隔符归一（空白上面已随 [第节\s] 删掉，这里只归一分隔符本身）：
        // 全角波浪 ～(U+FF5E)、全角减 －(U+FF0D)、数学减 −(U+2212)、en dash –、em dash —、
        // 半角 ~、汉字「至 / 到」一律当 '-'。不归一的话 "[05～06节]" 的区间正则收不到，
        // 整块会被当成「没认出节次」跳过（有声，但不是我们想要的结果）。
        var body = clean(source).replace(/[第节\s]/g, '').replace(/[（）()]/g, '')
            .replace(/[～－−–—~至到]/g, '-');
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
            // 分隔符已经归一到 '-'（见 periodsIn 开头），这里只认 '-'
            var range = /^(\d{1,2})\s*-\s*(\d{1,2})$/.exec(segment);
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

    var DAY_CHARS = '一二三四五六日天';

    function dayOfLabel(value) {
        var m = /(?:星期|周|礼拜)\s*([一二三四五六日天1-7])/.exec(clean(value));
        if (!m) return 0;
        var at = DAY_CHARS.indexOf(m[1]);
        if (at >= 0) return m[1] === '天' ? 7 : at + 1;
        return parseInt(m[1], 10);
    }

    // 「节次」列的标签（"第1-2节" / "1-2" / "上午"）：只在**表宽不足 8 列**、要拿它挪
    // 兜底窗口时用得上。
    function isPeriodLabel(value) {
        return /(第\s*\d|\d\s*[-—~至]\s*\d|上午|下午|晚上|早晨|中午|节)/.test(clean(value));
    }

    // 「2026-2027-1」→「2026-2027学年第一学期」（教务不给学期名时用它）
    function termNameFromCode(code) {
        var m = /^(\d{4})-(\d{4})-(\d)$/.exec(clean(code));
        if (!m) return '';
        if (m[3] === '1') return m[1] + '-' + m[2] + '学年第一学期';
        if (m[3] === '2') return m[1] + '-' + m[2] + '学年第二学期';
        if (m[3] === '3') return m[1] + '-' + m[2] + '学年第三学期';
        return m[1] + '-' + m[2] + '学年第' + m[3] + '学期';
    }

    function colOf(cell, index) {
        var col = cell ? cell.col : undefined;
        return (typeof col === 'number' && col >= 0) ? col : index;
    }

    function spanOf(cell) {
        var span = cell ? cell.span : undefined;
        return (typeof span === 'number' && span > 0) ? span : 1;
    }

    // 表宽 = 整张表用到的最大网格列数（把 colspan 算进去）。extract.js 交出来的 cols 最准；
    // 没有就按所有格子推。无表头兜底的窗口起点要用它 —— **不能用某一行的格子数**：
    // 首列被 rowspan 跨掉的那一行会少一个格子，按行宽算会把整行挪一天。
    var tableWidth = Number(data.cols) || 0;
    for (var wr = 0; wr < rows.length; wr++) {
        for (var wc = 0; wc < rows[wr].length; wc++) {
            var endAt = colOf(rows[wr][wc], wc) + spanOf(rows[wr][wc]);
            if (endAt > tableWidth) tableWidth = endAt;
        }
    }

    // 星期表头：找「没有课程内容、且能认出 ≥4 个星期标签」的那一行，记下每个星期在第几列。
    // 上游把「第几个格子」直接当星期几，课表首列是节次时整张表会错一天。
    var headerRow = -1;
    var dayCol = [0, -1, -1, -1, -1, -1, -1, -1];
    var bestLabels = 0;
    var headerLabelCells = 0;
    for (var hr = 0; hr < rows.length; hr++) {
        var hasContent = false;
        var labelCells = 0;
        var cols = [0, -1, -1, -1, -1, -1, -1, -1];
        var labels = 0;
        for (var hc = 0; hc < rows[hr].length; hc++) {
            if (rows[hr][hc].parts && rows[hr][hc].parts.length) hasContent = true;
            var day = dayOfLabel(rows[hr][hc].text);
            if (day < 1 || day > 7) continue;
            labelCells++;
            if (cols[day] >= 0) continue;
            cols[day] = colOf(rows[hr][hc], hc);
            labels++;
        }
        if (hasContent || labels < 4) continue;
        if (labels > bestLabels) {
            bestLabels = labels;
            headerRow = hr;
            headerLabelCells = labelCells;
            dayCol = cols;
        }
    }

    // 无表头（或表头只认出几天）时，剩下那几天落在哪一列：按**表宽**推「整张表最后 7 列
    // 是周一到周日」。表宽不足 8 列时窗口会从头开始，此时若首格是「节次」标签就往后挪一格。
    var fallbackStart = tableWidth > 7 ? tableWidth - 7 : 0;
    if (tableWidth <= 7) {
        for (var fr = 0; fr < rows.length; fr++) {
            if (rows[fr].length && isPeriodLabel(rows[fr][0].text)) {
                fallbackStart = 1;
                break;
            }
        }
    }

    var dayColOf = [0, -1, -1, -1, -1, -1, -1, -1];
    var dayGuessed = [false, false, false, false, false, false, false, false];
    var usedCols = {};
    var guessedDays = 0;
    var unplacedDays = 0;
    var dd;
    for (dd = 1; dd <= 7; dd++) {
        if (dayCol[dd] >= 0) {
            dayColOf[dd] = dayCol[dd];
            usedCols[dayCol[dd]] = true;
        }
    }
    for (dd = 1; dd <= 7; dd++) {
        if (dayColOf[dd] >= 0) continue;
        var guess = fallbackStart + dd - 1;
        if (guess < 0 || (tableWidth > 0 && guess >= tableWidth) || usedCols[guess]) {
            unplacedDays++;
            continue;
        }
        dayColOf[dd] = guess;
        dayGuessed[dd] = true;
        usedCols[guess] = true;
        guessedDays++;
    }

    // 网格列号 → 星期几（列号认不出来返回 0，调用方跳过那个格子）
    function dayOfGridCol(col) {
        for (var d = 1; d <= 7; d++) {
            if (dayColOf[d] === col) return d;
        }
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
            var d = dayOfGridCol(colOf(cell, ci));
            if (d < 1) continue;
            var parts = cell.parts || [];
            if (!parts.length) continue;
            // 跨列的合并格：它的课只落在**它覆盖的第一天**上（不复制到别的列去，
            // 那等于把课排到没排的星期），但要出声。
            if (spanOf(cell) > 1) mergedCells++;
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
                    // 同一个块里写了**几段**周次就产出几段（节次按各自那段走）：
                    // 之前只认第一段，第二段整段静默消失，连条与周次相关的警告都没有。
                    var specs = specsOf(blockHtml);
                    if (!specs.length) {
                        // 整块连一个「周次」/「节次」字段都没有 —— 认不出节次就不知道这门课放哪几节，
                        // 只能跳过，但必须报出来（不许静默丢课）
                        noPeriodBlocks++;
                        noteDropped(name, teacher, '没认出节次');
                        continue;
                    }
                    for (var sp = 0; sp < specs.length; sp++) {
                        var periods = specs[sp].periods ? periodsIn(specs[sp].periods) : null;
                        if (!periods) {
                            // 这一段认不出节次：跳过这一段并报出来，别的段照常产出
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
                        var weeks = weeksIn(specs[sp].weeks);
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

    // 作息表：本平台的两个校区作息都是 12 节。学期号（2026-2027-1 / -2）定冬夏令时：
    // 第一学期冬令时、第二学期夏令时（上游让用户在弹窗里选学期，两者只差第 5-8 节）。
    // 校区取不到（上游是弹窗问的）：默认共青城校区，写进 warnings 让用户核对。
    var termCode = clean(term.code);
    var termIndex = /^\d{4}-\d{4}-(\d)$/.exec(termCode);
    var summer = !!termIndex && termIndex[1] === '2';
    var baseTimes = summer ? GONGQINGCHENG_SUMMER : GONGQINGCHENG_WINTER;
    var seasonName = summer ? '夏令时（第二学期）' : '冬令时（第一学期）';

    // 课表里出现 >12 节（教务临时加的时段）时补出占位条目 —— 不补的话那些课会画到课表外面去。
    // 占位时刻写成 pad2(7 + 节次)：节次在解析阶段已夹进 MAX_PERIOD，所以补出来的时刻一定
    // 落在 08:00–23:45（合法 HH:mm、不跨天）—— 这两处的耦合写在 MAX_PERIOD 的注释里。
    var periodTimes = [];
    for (var pi = 0; pi < baseTimes.length; pi++) {
        periodTimes.push({
            periodIndex: baseTimes[pi].periodIndex,
            start: baseTimes[pi].start,
            end: baseTimes[pi].end
        });
    }
    for (var extra = PERIOD_COUNT + 1; extra <= maxPeriod; extra++) {
        periodTimes.push({
            periodIndex: extra,
            start: pad2(7 + extra) + ':00',
            end: pad2(7 + extra) + ':45'
        });
    }

    var termName = clean(term.name) || termNameFromCode(termCode);

    // extract.js 一定会带 now（提取时刻）；缺了就没法推算开学日，明确报错而不是瞎猜一个日期
    var firstDay = mondayOf(data.now);
    if (!firstDay) {
        throw new Error('提取数据里缺少 now（提取时刻），无法推算开学日期');
    }

    // 推算/假定出来的东西逐条说清楚（§4.2）：这些值在库里和真值长得一模一样，
    // 不说明用户就没有机会发现「现在第几周」是错的。
    pushWarning('开学日期无法从教务获取，已按最近的周一（' + firstDay + '）推算，请在「学期管理」里核对');
    pushWarning('教务页面不提供学期总周数：已按 ' + DEFAULT_TOTAL_WEEKS + ' 周起算，请在「学期管理」里核对');
    pushWarning('作息时间用的是适配器内置的「共青城校区·' + seasonName +
        '」12 节表（教务页面不提供，上游是让用户选校区）：上海路校区与另一个季节的第 5-12 节时间不同，请到「学期管理」里核对');
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
        var reasons = [];
        if (skippedBlocks > 0) reasons.push(skippedBlocks + ' 个没认出周次');
        if (noPeriodBlocks > 0) reasons.push(noPeriodBlocks + ' 个没认出节次');
        if (overPeriodBlocks > 0) reasons.push(overPeriodBlocks + ' 个节次超出 ' + MAX_PERIOD + ' 节');
        // 被跳过的块按名字点名（最多 5 个）—— 名单可能很长，pushWarning 会把它截到 200 字以内
        pushWarning('有 ' + reasons.join('、') + ' 的课程块已跳过（教务页面结构可能已调整，欢迎反馈）。' +
            droppedText());
    }
    if (ambiguousMarks > 0) {
        var marks = ambiguousSamples.length ? '（' + ambiguousSamples.join('、') + '）' : '';
        pushWarning('有 ' + ambiguousMarks + ' 处周次里的单双/隔周标记认不出来' + marks +
            '，这些周次已按「每周都上」处理，请核对导入结果');
    }
    if (bestLabels === 0) {
        pushWarning('课表里没找到星期表头，已按表宽 ' + tableWidth + ' 列推算（从第 ' + (fallbackStart + 1) +
            ' 列起对应周一到周日）对齐，课表可能整体错位，请核对导入结果');
    } else {
        if (guessedDays > 0) {
            pushWarning('星期表头只认出了 ' + bestLabels + ' 天，剩下 ' + guessedDays +
                ' 天已按表宽 ' + tableWidth + ' 列推算（从第 ' + (fallbackStart + 1) + ' 列起对应周一到周日），请核对导入结果');
        }
        if (headerLabelCells > 7) {
            pushWarning('表头里出现了 ' + headerLabelCells +
                ' 个星期标签，比星期一到星期日多：同一天以第一次出现的列为准，请核对导入结果');
        }
    }
    if (unplacedDays > 0) {
        pushWarning('有 ' + unplacedDays + ' 天没能在课表里找到对应的列（表宽 ' + tableWidth +
            ' 列不够放下整周），这些天的课不会被导入，请把课表截图反馈给适配器作者');
    }
    if (mergedCells > 0) {
        pushWarning('有 ' + mergedCells + ' 个格子横跨了多天，格里的课只按它覆盖的「第一天」导入，请核对导入结果');
    }
    if (!termName) {
        termName = '南昌航空大学科技学院课表';
        pushWarning('没能识别出学年学期名称，学期名已用「南昌航空大学科技学院课表」占位，请在「学期管理」里改名');
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
