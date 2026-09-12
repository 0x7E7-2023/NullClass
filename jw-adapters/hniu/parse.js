(function () {
    // 湖南信息职业技术学院（强智科技「高校综合管理教务系统」学生端 /jsxsd/）课表解析
    // 移植自 shiguang_warehouse 的 HNIU/hniu_01.js（MIT，作者 星河欲转）
    //   https://github.com/XingHeYuZhuan/shiguang_warehouse
    // 上游的核心是 parseTimetableToModel() + parseWeeks()：读 #timetable 里每个格子
    // div.kbcontent 的明细，按「课程名 / font[title=教师|周次(节次)|教室]」取字段。
    //
    // 与同批的 hynu（衡阳师范学院）是同一平台的近克隆，本文件**按这所学校自己的编码**单独写死了
    // 四处，逐处对照见同目录 AUDIT.md 的「与 hynu 的同平台对照片」：
    //   ① 作息表：上游 hniu 的 saveAppTimeSlots() 是这所学校自己的 12 节（08:30-09:10 … 21:10-21:50，
    //      与 hynu 那 12 节的时间**不同**），逐条照搬，没有沿用 hynu 的表；
    //   ② 节次：上游 hniu 专门为 [01-02-03-04节] 把正则从「1-2」改成了「取括号里所有数字的
    //      min/max」；但那样会把 hynu 那边的 [0102节]（两位一节连写）读成第 102 节。这里两种都认；
    //   ③ 合并：上游 hniu 比 hynu 多一段「相邻节次合并」（第1-2节 + 第3-4节 → 1-4），这里保留；
    //   ④ 周次：上游两版的 parseWeeks() 都是 weekStr.split('(')[0]，会把 1-16周(双) 吞成
    //      「每周都上」且不报警。这里按单/双切段，认不出的写法进 warnings。
    //
    // 输入是 extract.js 交出来的原始结构：
    //   { now: "2026-09-12", term: { code, name }, rows: [ [ { text, parts: [原始 HTML] }, … ], … ] }
    // 这里不碰 DOM（CI 的 Rhino 里没有），全部按字符串 + 正则处理，是纯函数。
    var data = JSON.parse(__ncInput);
    var rows = data.rows || [];
    var term = data.term || {};

    // 学校作息时间（教务处公布的 12 节）。上游硬编码在 saveAppTimeSlots() 里，逐条照搬 ——
    // 这是 hniu 自己的时间，不要拿 hynu 的那张表替换。
    var PERIOD_TIMES = [
        { periodIndex: 1, start: '08:30', end: '09:10' },
        { periodIndex: 2, start: '09:20', end: '10:00' },
        { periodIndex: 3, start: '10:20', end: '11:00' },
        { periodIndex: 4, start: '11:10', end: '11:50' },
        { periodIndex: 5, start: '14:00', end: '14:40' },
        { periodIndex: 6, start: '14:50', end: '15:30' },
        { periodIndex: 7, start: '15:50', end: '16:30' },
        { periodIndex: 8, start: '16:40', end: '17:20' },
        { periodIndex: 9, start: '18:40', end: '19:20' },
        { periodIndex: 10, start: '19:30', end: '20:10' },
        { periodIndex: 11, start: '20:20', end: '21:00' },
        { periodIndex: 12, start: '21:10', end: '21:50' }
    ];

    // 内置作息表每节的时长与课间（上游那张表 12 节全一致：40 分钟一节、课间 10 分钟）。
    // 课表里用到第 13 节以后时按同一节奏顺延补出来，并进 warnings。
    var SLOT_LENGTH = 40;
    var SLOT_BREAK = 10;
    // 能表达的节次上限：内置 12 节顺延两节（13: 22:00-22:40、14: 22:50-23:30）。
    // 再往后的作息时间会跨到第二天凌晨，认不出来 —— 那样的块按「解析不了」处理并进 warnings，
    // 不许把一个凭空算出来的时间写进课表（也不许静默丢）。
    var MAX_PERIOD = 14;

    // 上游写死的学期总周数（教务页面不给这个值，只能照抄并进 warnings）
    var DEFAULT_TOTAL_WEEKS = 20;
    // 载荷校验的总周数上限（1..30），超出的周次按脏数据丢弃并进 warnings
    var MAX_WEEK = 30;
    var MAX_WARNING_TEXT = 200;
    var MAX_WARNINGS = 20;

    // 一个格子里放多门课时，教务用一长串减号分隔（上游写死 21/22 个，这里放宽到 5 个以上）
    var DASHES = /-{5,}/;
    var TEACHER_TITLE = /^(教师|老师|任课教师|授课教师|教师姓名)$/;
    var ROOM_TITLE = /^(教室|上课地点|上课教室|地点|教室名称)$/;
    var COURSE_TITLE = /^(课程|课程名称|课程名|科目)$/;
    // 只认上游读过的那两类字段标题。**不**把「时间」算进来：强智的明细里没有这一条，
    // 而一旦有 font[title=时间] 装着「08:30-09:10」这种时刻，按节次去解会得到 8-30 这样的垃圾。
    var SPEC_TITLE = /周次|节次/;

    var skippedSpecs = [];
    var skippedCount = 0;
    var droppedWeeks = 0;
    var labelPeriods = 0;
    var ignoredMarks = [];
    var warnings = [];

    function warn(message) {
        if (warnings.length >= MAX_WARNINGS) return;
        var text = message.length > MAX_WARNING_TEXT ? message.substring(0, MAX_WARNING_TEXT) : message;
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

    // "21:50" → 1310；不是 HH:mm 返回 -1
    function minutesOf(hhmm) {
        var m = /^(\d{1,2}):(\d{2})$/.exec(clean(hhmm));
        if (!m) return -1;
        return parseInt(m[1], 10) * 60 + parseInt(m[2], 10);
    }

    // "21:50" + 10 → "22:00"；跨到第二天（>= 24:00）返回空串，调用处据此停止顺延
    function addMinutes(hhmm, delta) {
        var total = minutesOf(hhmm);
        if (total < 0) return '';
        var sum = total + delta;
        if (sum >= 24 * 60) return '';
        var h = Math.floor(sum / 60);
        var mi = sum % 60;
        return (h < 10 ? '0' : '') + h + ':' + (mi < 10 ? '0' : '') + mi;
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

    // 课程名：明细里第一个 <font> 之前的第一行文字（与上游取「第一个非空文本节点」等价）；
    // 有的学校把课名也放进 font[title=课程]，兜底认一下。
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

    function titleTexts(fonts, pattern) {
        var out = [];
        for (var i = 0; i < fonts.length; i++) {
            if (!pattern.test(fonts[i].title)) continue;
            var text = fonts[i].text;
            if (text && out.indexOf(text) < 0) out.push(text);
        }
        return out;
    }

    // 「周次(节次)」一条 font 里既有周次也有节次（上游读的就是这条），
    // 有的强智学校拆成「周次」和「节次」两条 font —— 两种都切开。
    // 节次优先认方括号（上游的正则就是找 [..节]）；没有方括号时认「(第1-2节)」这种带「节」字的括号，
    // 但不认纯数字的括号 —— 那是教学班序号，不是节次。
    function splitWeekPeriod(value) {
        var text = clean(value);
        var bracket = /\[([^\]]*)\]/.exec(text);
        if (bracket) {
            return {
                weeks: clean(text.substring(0, bracket.index) + ' ' + text.substring(bracket.index + bracket[0].length)),
                periods: clean(bracket[1])
            };
        }
        var paren = /[(（]([^()（）]*节[^()（）]*)[)）]/.exec(text);
        if (paren) {
            return {
                weeks: clean(text.substring(0, paren.index) + ' ' + text.substring(paren.index + paren[0].length)),
                periods: clean(paren[1])
            };
        }
        return { weeks: text, periods: '' };
    }

    // 把「周次」「节次」两类 font 配成一条排课记录：一条合写的（周次(节次)）直接成对，
    // 拆写的（周次 + 节次）相邻两条配成一对。
    function specsOf(blockHtml) {
        var fonts = fontsOf(blockHtml);
        var specs = [];
        var pending = null;
        for (var i = 0; i < fonts.length; i++) {
            if (!SPEC_TITLE.test(fonts[i].title)) continue;
            var split = splitWeekPeriod(fonts[i].text);
            if (!split.weeks && !split.periods) continue;
            if (split.weeks && split.periods) {
                if (pending) specs.push(pending);
                pending = null;
                specs.push(split);
            } else if (split.weeks) {
                if (pending && !pending.weeks) {
                    pending.weeks = split.weeks;
                    specs.push(pending);
                    pending = null;
                } else {
                    if (pending) specs.push(pending);
                    pending = { weeks: split.weeks, periods: '' };
                }
            } else if (pending && !pending.periods) {
                pending.periods = split.periods;
                specs.push(pending);
                pending = null;
            } else {
                if (pending) specs.push(pending);
                pending = { weeks: '', periods: split.periods };
            }
        }
        if (pending) specs.push(pending);
        return specs;
    }

    function pushWeek(out, week, oddOnly, evenOnly) {
        if (!(week >= 1)) return;
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

    // 周次："1-9,11-17" / "1-4,6-8,10-16" / "1-15(单)" / "1-16周(双)" / "单周1-16" / "第3周"
    //   → 周次数组。
    // 上游这一段的实现是 weekStr.split('(')[0]：括号及其后面的一切都丢掉，于是
    // 「1-16(周)(双)」「2-16周(双)」都变成「每周都上」，而且一声不吭 —— 这是第一批移植
    // 挖出来的头号坑。这里改成：
    //   ① 先摘掉节次方括号、"(周)"、以及**纯数字的括号**（"(1)" 是教学班序号，不是周次 ——
    //      它要是被当成周次，会往课表里塞进一门课根本没上的那一周）；
    //   ② 「单/双」按它所在的段生效（写在括号里、写在「周」字后面、写成「单周1-16」都算）；
    //   ③ 既认不出单双（「隔周」）、又没法当每周处理的段，记进 warnings。
    function weeksIn(source) {
        var text = clean(source);
        text = text.replace(/\[[^\]]*\]/g, ' ');
        text = text.replace(/[（(]\s*周\s*[)）]/g, ' ');
        text = text.replace(/[（(]\s*\d[\d\s\-—~至,，、]*[)）]/g, ' ');
        var segments = text.split(/[,，、;；]/);
        var weeks = [];
        for (var i = 0; i < segments.length; i++) {
            var segment = segments[i];
            if (!segment) continue;
            var oddOnly = segment.indexOf('单') >= 0;
            var evenOnly = segment.indexOf('双') >= 0;
            if (oddOnly && evenOnly) {
                // 「单双混在一段里」认不出到底上哪几周：不猜，按每周放进去并出声
                noteIgnoredMark(segment);
                oddOnly = false;
                evenOnly = false;
            }
            // 「隔周」这类要求「和上一周隔开」的写法，没有上一周做基准就定不了单双：
            // 数字照样取出来（按每周），但必须进 warnings —— 不许静默退化成「每周都上」
            if (segment.indexOf('隔') >= 0) noteIgnoredMark(segment);
            var range = /(\d{1,2})\s*[-—~至]\s*(\d{1,2})/.exec(segment);
            if (range) {
                var start = parseInt(range[1], 10);
                var end = parseInt(range[2], 10);
                for (var w = start; w <= end; w++) pushWeek(weeks, w, oddOnly, evenOnly);
            } else {
                var single = /(\d{1,2})/.exec(segment);
                if (single) pushWeek(weeks, parseInt(single[1], 10), oddOnly, evenOnly);
            }
        }
        return uniqueSorted(weeks);
    }

    function noteIgnoredMark(segment) {
        var text = clean(segment);
        if (!text) return;
        if (ignoredMarks.indexOf(text) >= 0) return;
        if (ignoredMarks.length >= 5) return;
        ignoredMarks.push(text);
    }

    // 节次：方括号里的内容。上游只认「[1-2节]」和「[01-02-03-04节]」两种写法里的数字，
    // 这里把同一类写法一次收齐（都不是靠猜，靠的是「括号里的数字就是节次」这条教务约定）：
    //   "01-02节" / "1-2节" → 1-2（区间）
    //   "01-02-03-04节"     → 1-4（多段连写，上游 hniu 专门为它改过正则）
    //   "0102节" / "030405节" → 1-2 / 1-3（两位一节连写，上游 hynu 那边是这种）
    //   "第9,10节"          → 9-10
    // 解不出来的返回 null（调用处会记进 warnings，不静默丢课）。
    function periodsIn(source) {
        var body = clean(source).replace(/[第节\s]/g, '').replace(/[（）()]/g, '');
        if (!body) return null;
        var start = null;
        var end = null;
        var bad = false;
        function note(value) {
            if (!(value >= 1) || value > MAX_PERIOD) {
                bad = true;
                return;
            }
            if (start === null || value < start) start = value;
            if (end === null || value > end) end = value;
        }
        function noteToken(token) {
            if (!/^\d+$/.test(token)) {
                bad = true;
                return;
            }
            if (token.length >= 4 && token.length % 2 === 0) {
                for (var k = 0; k < token.length; k += 2) note(parseInt(token.substring(k, k + 2), 10));
            } else {
                note(parseInt(token, 10));
            }
        }
        var groups = body.split(/[,，、;；]/);
        for (var i = 0; i < groups.length; i++) {
            var tokens = groups[i].split(/[-—~至]/);
            for (var t = 0; t < tokens.length; t++) {
                if (!tokens[t]) continue;
                noteToken(tokens[t]);
            }
        }
        if (bad || start === null || end === null) return null;
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

    function termNameFromCode(code) {
        var m = /^(\d{4})-(\d{4})-(\d)$/.exec(clean(code));
        if (!m) return '';
        if (m[3] === '1') return m[1] + '-' + m[2] + '学年第一学期';
        if (m[3] === '2') return m[1] + '-' + m[2] + '学年第二学期';
        if (m[3] === '3') return m[1] + '-' + m[2] + '学年第三学期';
        return m[1] + '-' + m[2] + '学年第' + m[3] + '学期';
    }

    // 同一门课、同一天、同一周次、同一教室，且节次相邻或重叠（第1-2节 + 第3-4节）→ 合成一块。
    // 这一段是上游 hniu 比 hynu 多出来的逻辑（hynu 没有），强智把连堂课拆在相邻两行的两格里，
    // 不合并的话课表上同一门连堂课会画成两块、中间多一道缝。
    function mergeAdjacent(blocks) {
        var list = [];
        var i;
        for (i = 0; i < blocks.length; i++) list.push(blocks[i]);
        list.sort(function (a, b) {
            if (a.dayOfWeek !== b.dayOfWeek) return a.dayOfWeek - b.dayOfWeek;
            if (a.startWeek !== b.startWeek) return a.startWeek - b.startWeek;
            if (a.endWeek !== b.endWeek) return a.endWeek - b.endWeek;
            if (a.weekType !== b.weekType) return a.weekType < b.weekType ? -1 : 1;
            var la = a.location || '';
            var lb = b.location || '';
            if (la !== lb) return la < lb ? -1 : 1;
            if (a.startPeriod !== b.startPeriod) return a.startPeriod - b.startPeriod;
            return a.endPeriod - b.endPeriod;
        });
        var out = [];
        for (i = 0; i < list.length; i++) {
            var block = list[i];
            var last = out.length ? out[out.length - 1] : null;
            if (last &&
                last.dayOfWeek === block.dayOfWeek &&
                last.startWeek === block.startWeek &&
                last.endWeek === block.endWeek &&
                last.weekType === block.weekType &&
                last.location === block.location &&
                block.startPeriod <= last.endPeriod + 1) {
                if (block.endPeriod > last.endPeriod) last.endPeriod = block.endPeriod;
            } else {
                out.push(block);
            }
        }
        return out;
    }

    // 星期表头：找「没有课程内容、且有 ≥4 个星期标签」的那一行，按它的列号取每天那一列。
    // 上游把「第几个格子」直接当星期几（cells.forEach 的 dayIndex + 1），首列是节次列时
    // 整张表会错一天；hynu 也是这么改的，两个适配器共用这条对齐方式。
    var headerRow = -1;
    var dayCol = [0, -1, -1, -1, -1, -1, -1, -1];
    var bestLabels = 0;
    var r;
    var c;
    for (r = 0; r < rows.length; r++) {
        var hasContent = false;
        var cols = [0, -1, -1, -1, -1, -1, -1, -1];
        var labels = 0;
        for (c = 0; c < rows[r].length; c++) {
            if (rows[r][c].parts && rows[r][c].parts.length) hasContent = true;
            var day = dayOfLabel(rows[r][c].text);
            if (day >= 1 && day <= 7 && cols[day] < 0) {
                cols[day] = c;
                labels++;
            }
        }
        if (hasContent || labels < 4) continue;
        if (labels > bestLabels) {
            bestLabels = labels;
            headerRow = r;
            dayCol = cols;
        }
    }

    var order = [];
    var byCourse = {};

    for (var ri = 0; ri < rows.length; ri++) {
        if (ri === headerRow) continue;
        var row = rows[ri];
        // 这一行的「第N节」标签（首列）。只在格子自己的节次解不出来时兜底用 ——
        // 上游遇到这种格子是直接丢掉的（上游那句是 if (name && weekStr && start > 0)），一声不吭。
        var rowPeriods = row.length ? periodsIn(row[0].text) : null;
        // 没认出表头时的兜底：多于 7 列就按「最后 7 列是周一到周日」算
        //（首列是节次列是最常见的多出那一列），并进 warnings 说明。
        var offset = bestLabels >= 4 ? 0 : (row.length > 7 ? row.length - 7 : 0);
        for (var d = 1; d <= 7; d++) {
            var col = bestLabels >= 4 ? dayCol[d] : offset + d - 1;
            if (col < 0 || col >= row.length) continue;
            var parts = row[col].parts || [];
            for (var p = 0; p < parts.length; p++) {
                var blocks = String(parts[p]).split(DASHES);
                for (var b = 0; b < blocks.length; b++) {
                    var blockHtml = blocks[b];
                    if (plainText(blockHtml) === '') continue;
                    var name = nameOf(blockHtml);
                    if (!name) {
                        skippedCount++;
                        continue;
                    }
                    var fonts = fontsOf(blockHtml);
                    var teachers = titleTexts(fonts, TEACHER_TITLE);
                    var rooms = titleTexts(fonts, ROOM_TITLE);
                    var specs = specsOf(blockHtml);
                    if (!specs.length) {
                        skippedCount++;
                        if (skippedSpecs.indexOf(name) < 0) skippedSpecs.push(name);
                        continue;
                    }
                    var key = name + '|' + teachers.join(',');
                    var course = null;
                    for (var s = 0; s < specs.length; s++) {
                        var weeks = weeksIn(specs[s].weeks);
                        var periods = periodsIn(specs[s].periods);
                        if (!periods && weeks.length && rowPeriods) {
                            // 格子里没写节次，但这一行的「第N节」标签读得出来 —— 用它，并计数出声
                            periods = rowPeriods;
                            labelPeriods++;
                        }
                        if (!weeks.length || !periods) {
                            skippedCount++;
                            if (skippedSpecs.indexOf(name) < 0) skippedSpecs.push(name);
                            continue;
                        }
                        // 课程要等真产出一条安排才登记：周次/节次解析不出来的块只进 warnings，
                        // 不能在课表里留下一门「没有任何安排」的空课。
                        if (!course) {
                            if (!byCourse[key]) {
                                byCourse[key] = {
                                    name: name,
                                    teacher: teachers.length ? teachers.join(',') : null,
                                    note: null,
                                    blocks: [],
                                    seen: {}
                                };
                                order.push(key);
                            }
                            course = byCourse[key];
                        }
                        var runs = runsOf(weeks);
                        for (var n = 0; n < runs.length; n++) {
                            var run = runs[n];
                            var item = {
                                dayOfWeek: d,
                                startPeriod: periods.start,
                                endPeriod: periods.end,
                                startWeek: run.start,
                                endWeek: run.end,
                                weekType: run.weekType,
                                location: rooms.length ? rooms[0] : null
                            };
                            var blockKey = [item.dayOfWeek, item.startPeriod, item.endPeriod,
                                item.startWeek, item.endWeek, item.weekType,
                                item.location || ''].join('|');
                            if (course.seen[blockKey]) continue;
                            course.seen[blockKey] = true;
                            course.blocks.push(item);
                        }
                    }
                }
            }
        }
    }

    if (order.length === 0) {
        throw new Error(
            '本学期没有解析到任何课程：可能是还没排课，或教务页面改了结构（也可能登录状态已失效）'
        );
    }

    var maxWeek = 0;
    var maxPeriod = 0;
    var courses = [];
    for (var oi = 0; oi < order.length; oi++) {
        var entry = byCourse[order[oi]];
        var merged = mergeAdjacent(entry.blocks);
        for (var bi = 0; bi < merged.length; bi++) {
            if (merged[bi].endWeek > maxWeek) maxWeek = merged[bi].endWeek;
            if (merged[bi].endPeriod > maxPeriod) maxPeriod = merged[bi].endPeriod;
        }
        courses.push({ name: entry.name, teacher: entry.teacher, note: entry.note, blocks: merged });
    }

    var totalWeeks = maxWeek > DEFAULT_TOTAL_WEEKS ? maxWeek : DEFAULT_TOTAL_WEEKS;
    if (totalWeeks > MAX_WEEK) totalWeeks = MAX_WEEK;

    // 作息表覆盖到课表里真正用到的每一节：学校那张表只列了 12 节，
    // 用到第 13 节以后时按同一节奏（40 分钟一节、课间 10 分钟）顺延补出来并出声。
    var periodTimes = [];
    var pi;
    for (pi = 0; pi < PERIOD_TIMES.length; pi++) periodTimes.push(PERIOD_TIMES[pi]);
    var extended = 0;
    if (maxPeriod > periodTimes.length) {
        var cursor = periodTimes[periodTimes.length - 1].end;
        while (periodTimes.length < maxPeriod) {
            var nextStart = addMinutes(cursor, SLOT_BREAK);
            var nextEnd = addMinutes(nextStart, SLOT_LENGTH);
            if (!nextStart || !nextEnd || minutesOf(nextStart) >= minutesOf(nextEnd)) break;
            periodTimes.push({ periodIndex: periodTimes.length + 1, start: nextStart, end: nextEnd });
            cursor = nextEnd;
            extended++;
        }
    }

    var termName = clean(term.name) || termNameFromCode(term.code);

    // extract.js 一定会带 now（提取时刻）；缺了就没法推算开学日，明确报错而不是瞎猜一个日期
    var firstDay = mondayOf(data.now);
    if (!firstDay) {
        throw new Error('提取数据里缺少 now（提取时刻），无法推算开学日期');
    }

    // 推算/假定出来的东西逐条说清楚（§4.2）：这些值在库里和真值长得一模一样，
    // 不说明用户就没有机会发现「现在第几周」是错的。
    warn('开学日期无法从教务获取，已按最近的周一（' + firstDay + '）推算，请在「学期管理」里核对');
    warn('教务页面不提供作息时间：节次时间按适配器内置的 ' + PERIOD_TIMES.length + ' 节设定，请在「学期管理」里核对');
    if (maxWeek > DEFAULT_TOTAL_WEEKS) {
        warn('课表里用到了第 ' + maxWeek + ' 周，超过适配器内置的 ' + DEFAULT_TOTAL_WEEKS +
            ' 周：学期总周数已按 ' + totalWeeks + ' 周导入，请在「学期管理」里核对');
    } else {
        warn('教务页面不提供学期总周数：已按适配器内置的 ' + DEFAULT_TOTAL_WEEKS + ' 周导入，请在「学期管理」里核对');
    }
    if (droppedWeeks > 0) {
        warn('有 ' + droppedWeeks + ' 条周次超出 ' + MAX_WEEK + ' 周，已按脏数据丢弃');
    }
    if (skippedCount > 0) {
        var names = skippedSpecs.length ? '：' + skippedSpecs.slice(0, 3).join('、') +
            (skippedSpecs.length > 3 ? ' 等' : '') : '';
        warn('有 ' + skippedCount + ' 处课程信息没能解析出周次或节次，已跳过' + names +
            '（教务页面结构可能已调整，欢迎反馈给适配器）');
    }
    if (labelPeriods > 0) {
        warn('有 ' + labelPeriods + ' 处课程信息里没写节次，已按所在行的节次标签放入，请核对');
    }
    if (ignoredMarks.length > 0) {
        warn('有认不出单双周的周次写法（如「' + ignoredMarks.join('」「') +
            '」），已按每周都上放进课表，请核对');
    }
    if (extended > 0) {
        warn('课表里用到了第 ' + maxPeriod + ' 节，超过适配器内置作息表的 ' + PERIOD_TIMES.length +
            ' 节：第 ' + (PERIOD_TIMES.length + 1) + '-' + maxPeriod +
            ' 节的上下课时间已按「每节 ' + SLOT_LENGTH + ' 分钟、课间 ' + SLOT_BREAK + ' 分钟」顺延补出，请核对');
    }
    if (bestLabels < 4) {
        warn('课表里没找到星期表头，已按每行最后 7 列对应周一到周日，请核对导入结果');
    }
    if (!termName) {
        termName = '湖南信息职业技术学院课表';
        warn('没能识别出学年学期名称，学期名已用「湖南信息职业技术学院课表」占位，请在「学期管理」里改名');
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
