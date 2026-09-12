(function () {
    // 淮北理工学院（湖南强智 · 高校综合管理教务系统）课表解析 —— 第二步：教务原始行 → 空课课表载荷。
    // 移植自 shiguang_warehouse 的 HBLGXY/hblgxy_01.js（MIT，作者 星河欲转）
    //   https://github.com/XingHeYuZhuan/shiguang_warehouse
    // 上游的核心是 parseTimetableToModel() + parseWeeks()：读 #timetable 每个格子里
    // div.kbcontent 的明细，按「课程名 / font[title=教师|周次(节次)|教室]」取字段。
    // 字段来源与上游一致（同一个平台的同一套页面结构），改动在这七处，逐条对应 AUDIT.md：
    //   ① 星期按**表头列号**对齐（上游把「第几个格子」直接当星期几；课表首列是节次列时整表错一天）；
    //   ② 周次认「单/双」，写在「周」字后面（1-16周(双)）或前面（双周1-16）都算数 ——
    //      上游 split('(')[0] 会把单/双整段丢掉，1-16周(双) 变成「每周都上」且不报警；
    //   ③ 括号里的纯数字（(1)）或数字区间（(1-2)）是教学班序号，不是周次：当成周次会
    //      凭空多出一段第 1 周，或者把 (1-2)1-16周 里的 1-16 整段丢掉只剩两周；
    //   ④ 节次读不出来时回落到所在行的「第N节」标签（含 rowSpan 继承：标签格子被并掉时沿用上一行的），
    //      两边都读不出才跳过并计数进 warnings（上游是静默丢课）；
    //   ⑤ 作息时间用上游那张 14 节表，并检查课表实际用到的节次是否都在表内，缺的进 warnings；
    //   ⑥ 总周数被课表里更晚的周次抬高时进 warnings（推算值不说，用户没有机会发现）；
    //   ⑦ 解析不出来的课程块逐类计数进 warnings，不静默丢。
    //
    // 这一步是纯转换：没有网络、没有 DOM（CI 的 Rhino 里两个都没有），只吃字符串和正则。
    var data = JSON.parse(__ncInput);
    if (!data || typeof data !== 'object') {
        throw new Error('适配器没有拿到教务数据：请重新登录教务系统后再点「提取课表」');
    }
    var rows = data.rows || [];
    var term = data.term || {};

    // 学校作息时间：上游 saveAppTimeSlots() 里那张表，逐条照搬（14 节）。
    // 第 13/14 节是上游脚本自己带的（23:25 前后），课表里排不到就不会显示。
    var PERIOD_TIMES = [
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
        { periodIndex: 12, start: '21:45', end: '22:30' },
        { periodIndex: 13, start: '22:40', end: '23:25' },
        { periodIndex: 14, start: '23:25', end: '23:59' }
    ];

    // 上游写死的学期总周数（教务页面不给这个值）；课表里有更晚的周次时按更晚的给，并进 warnings。
    var DEFAULT_TOTAL_WEEKS = 20;
    // 载荷校验 totalWeeks ∈ 1..30：超出这个范围的周次当脏数据丢弃并计数
    var MAX_WEEK = 30;
    // 一个格子里放多门课时，教务用一长串减号分隔（上游写死 21/22 个，这里放宽到 5 个以上）
    var DASHES = /-{5,}/;
    var TEACHER_TITLE = /^(教师|老师|任课教师|授课教师|教师姓名)$/;
    var ROOM_TITLE = /^(教室|上课地点|上课教室|地点|教室名称)$/;
    var COURSE_TITLE = /^(课程|课程名称|课程名|科目)$/;
    var SPEC_TITLE = /周次|节次/;   // 别把「时间」并进来：标着「上课时间」的 font 内容是钟点（11:10-11:50），
    // 会被下面的区间正则读成第 10-11 周，凭空造出排课。上游只查固定 title，这个宽匹配是移植时自己加的。
    // 行首的节次标签：「第1-2节」「1-2」「第9,10节」「第3节」都算，整格文字必须只由节次构成
    var PERIOD_LABEL = /^第?\s*\d{1,2}\s*([-—~,，]\s*\d{1,2})*\s*节?$/;
    // 括号里的纯数字组（教学班序号）
    var SERIAL_PAREN = /[（(]\s*\d+\s*[)）]/g;
    // 周次串**开头**的序号组：括号里是纯数字或数字区间（(1)、(1-2)）都算教学班序号，不是周次。
    // 强智把序号直接拼在周次前面（(1-2)1-16周），只剥纯数字的话这个 (1-2) 会被读成
    // 「第 1-2 周」，一门 1-16 周的课就只剩开头两周，而且一声不吭。
    // 括号里带「周」「单」「双」或其他字的（(1-2周)、1-16(周)、1-15(单)）是真正的周次形态，不许剥。
    var SERIAL_HEAD = /^\s*[（(]\s*\d+\s*(?:[-—~至]\s*\d+\s*)?[)）]/;

    var droppedWeeks = 0;
    var noNameBlocks = 0;
    var noSpecBlocks = 0;
    var noWeekBlocks = 0;
    var noPeriodBlocks = 0;
    var labelFilled = 0;

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

    // 「周次(节次)」一条 font 里既有周次也有节次（上游这所学校就是），
    // 有的强智学校拆成「周次」和「节次」两条 font —— 两种都按「方括号前是周次、方括号里是节次」切。
    function splitWeekPeriod(value) {
        var text = clean(value);
        var bracket = /\[([^\]]*)\]/.exec(text);
        if (!bracket) return { weeks: text, periods: '' };
        return {
            weeks: clean(text.substring(0, bracket.index)),
            periods: clean(bracket[1])
        };
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
        if (isNaN(week) || week < 1) return;
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

    // 周次：「1-9,11-17(周)」「12-15(周)」「1-16周(双)」「双周1-16」「第3周」都认。
    // 四步预处理，每一步都对应一个踩过的坑：
    //   ① 去掉方括号里的节次（万一整串「周次(节次)」都传进来，别把 [01-02节] 读成数字）；
    //   ② 串首的序号组（(1)、(1-2)）整组去掉：那是教学班序号，不是周次。必须放在「周」字
    //      删掉**之前**做 —— 「周」先没了的话，(1-2周) 这种正常写法也会长得像序号被误剥。
    //      只剥串首那一个：串中出现的数字区间仍按原样交给下面的周次逻辑（拿不准的不静默改写）；
    //   ③ 括号里的纯数字（(1)）同上：留着会凭空多出「第 1 周」，
    //      一门 9-16 周的课会被加出一段第 1 周（上游没这问题是因为它把 '(' 之后整个丢掉了，
    //      但那也顺手丢掉了单/双 —— 见 ④）；
    //   ④ 「周」是量词，整字删掉（不是换成空格）：换成空格会把紧跟其后的「(双)」切成另一段，
    //      单/双标记于是整段丢掉 —— 「1-16周(双)」会退化成每周都上，而且不报警。
    function weeksIn(source) {
        var text = clean(source)
            .replace(/\[[^\]]*\]/g, ' ')
            .replace(SERIAL_HEAD, ' ')
            .replace(SERIAL_PAREN, ' ')
            .replace(/周/g, '')
            .replace(/[（(]\s*[)）]/g, ' ');
        var tokens = text.split(/[,，、;；\s]+/);
        var segments = [];
        var i;
        for (i = 0; i < tokens.length; i++) {
            if (tokens[i]) segments.push(tokens[i]);
        }
        // 单/双也可能被空白切成独立的一段（「1-16周 双」）：并给最近的周次段，免得标记再丢一次
        for (i = 0; i < segments.length; i++) {
            if (/\d/.test(segments[i])) continue;
            if (segments[i].indexOf('单') < 0 && segments[i].indexOf('双') < 0) continue;
            var into = -1;
            for (var back = i - 1; back >= 0; back--) {
                if (/\d/.test(segments[back])) { into = back; break; }
            }
            for (var fwd = i + 1; into < 0 && fwd < segments.length; fwd++) {
                if (/\d/.test(segments[fwd])) { into = fwd; break; }
            }
            if (into < 0) continue;
            segments[into] = segments[into] + segments[i];
            segments[i] = '';
        }
        var weeks = [];
        for (i = 0; i < segments.length; i++) {
            var segment = segments[i];
            if (!segment || !/\d/.test(segment)) continue;
            var onlyOdd = segment.indexOf('单') >= 0;
            var onlyEven = segment.indexOf('双') >= 0;
            // 「单双周」两个标记同时出现 = 每周都上；不这样兜底，两边互相排除会把这段周次清空
            if (onlyOdd && onlyEven) { onlyOdd = false; onlyEven = false; }
            var numbers = segment.match(/\d+/g);
            if (numbers.length >= 2) {
                var start = parseInt(numbers[0], 10);
                var end = parseInt(numbers[1], 10);
                if (end < start) { var swap = start; start = end; end = swap; }
                for (var week = start; week <= end; week++) pushWeek(weeks, week, onlyOdd, onlyEven);
            } else {
                pushWeek(weeks, parseInt(numbers[0], 10), onlyOdd, onlyEven);
            }
        }
        return uniqueSorted(weeks);
    }

    // 节次：方括号里的内容。"01-02节" / "1-2节" / "0708节"（连堂写成两位一节） / "第9,10节" 都认。
    // 括号里的纯数字组（(1)）是教学班序号：先整组去掉，否则「01-02节(1)」去掉括号会变成「01-021」，
    // 这一门课的节次直接读废（紧跟其后的回落是「按行标签补」，会补出错的节次）。
    function periodsIn(source) {
        var body = clean(source)
            .replace(SERIAL_PAREN, ' ')
            .replace(/[第节\s]/g, '')
            .replace(/[（）()]/g, '');
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

    // 行首的「第N节」标签 → 节次范围。整格文字就是一串节次才认（「第1-2节」「1-2」「第9,10节」），
    // 免得把课程格子里的文字当标签。
    function rowLabelOf(row) {
        for (var c = 0; c < row.length; c++) {
            var text = clean(row[c].text);
            if (!text || !PERIOD_LABEL.test(text)) continue;
            var periods = periodsIn(text);
            if (periods) return periods;
        }
        return null;
    }

    function hasContent(row) {
        for (var c = 0; c < row.length; c++) {
            if (row[c].parts && row[c].parts.length) return true;
        }
        return false;
    }

    // 星期表头：找「没有课程内容、且有 ≥4 个星期标签」的那一行，按它的列号取每天那一列。
    // 上游把「第几个格子」直接当星期几，课表首列是节次时整张表会错一天。
    var headerRow = -1;
    var headerWidth = 0;
    var dayCol = [0, -1, -1, -1, -1, -1, -1, -1];
    var bestLabels = 0;
    for (var r = 0; r < rows.length; r++) {
        var hasParts = false;
        var cols = [0, -1, -1, -1, -1, -1, -1, -1];
        var labels = 0;
        for (var c = 0; c < rows[r].length; c++) {
            if (rows[r][c].parts && rows[r][c].parts.length) hasParts = true;
            var day = dayOfLabel(rows[r][c].text);
            if (day >= 1 && day <= 7 && cols[day] < 0) {
                cols[day] = c;
                labels++;
            }
        }
        if (hasParts || labels < 4) continue;
        if (labels > bestLabels) {
            bestLabels = labels;
            headerRow = r;
            headerWidth = rows[r].length;
            dayCol = cols;
        }
    }

    var courses = [];
    var byName = {};
    var lastLabel = null;
    var shortRows = 0;

    for (var ri = 0; ri < rows.length; ri++) {
        if (ri === headerRow) continue;
        var row = rows[ri];
        // 行首标签：这一行没有标签格子（rowSpan 并到上一行）时沿用上一行的 —— 这就是 rowSpan 继承
        var ownLabel = rowLabelOf(row);
        if (ownLabel) lastLabel = ownLabel;
        var rowPeriods = ownLabel || lastLabel;
        // 列对齐：认出了表头就按表头的列号取每天那一列；这一行比表头短（行首的节次格子被
        // rowSpan 并进了上一行，DOM 里整行往前挪了一格）时改按「该行最后 7 列是周一到周日」，
        // 否则整行的课会错一天。一个表头都没认出来时同理（首列是节次列是最常见的多出那一列）。
        var useHeaderCols = bestLabels >= 4 && row.length >= headerWidth;
        if (bestLabels >= 4 && !useHeaderCols && hasContent(row)) shortRows++;
        var offset = useHeaderCols ? 0 : (row.length > 7 ? row.length - 7 : 0);
        for (var d = 1; d <= 7; d++) {
            var col = useHeaderCols ? dayCol[d] : offset + d - 1;
            if (col < 0 || col >= row.length) continue;
            var parts = row[col].parts || [];
            for (var p = 0; p < parts.length; p++) {
                var blocks = String(parts[p]).split(DASHES);
                for (var b = 0; b < blocks.length; b++) {
                    var blockHtml = blocks[b];
                    if (plainText(blockHtml) === '') continue;
                    var name = nameOf(blockHtml);
                    if (!name) {
                        noNameBlocks++;
                        continue;
                    }
                    var fonts = fontsOf(blockHtml);
                    var teachers = titleTexts(fonts, TEACHER_TITLE);
                    var rooms = titleTexts(fonts, ROOM_TITLE);
                    var specs = specsOf(blockHtml);
                    if (!specs.length) {
                        noSpecBlocks++;
                        continue;
                    }
                    // 课名/教师名直接当对象键会和 Object.prototype 上的名字（constructor / toString 之类）
                    // 撞车，所以统一加一个前缀
                    var teacherKey = teachers.join(',');
                    var bucket = byName['@' + name];
                    if (!bucket) {
                        bucket = {};
                        byName['@' + name] = bucket;
                    }
                    var course = bucket['@' + teacherKey];
                    for (var s = 0; s < specs.length; s++) {
                        var weeks = weeksIn(specs[s].weeks);
                        if (!weeks.length) {
                            noWeekBlocks++;
                            continue;
                        }
                        var periods = periodsIn(specs[s].periods);
                        var usedLabel = false;
                        if (!periods && rowPeriods) {
                            periods = rowPeriods;
                            usedLabel = true;
                        }
                        if (!periods) {
                            noPeriodBlocks++;
                            continue;
                        }
                        // 课程要等真产出一条安排才登记：周次/节次解析不出来的块只进 warnings，
                        // 不能在课表里留下一门「没有任何安排」的空课。
                        if (!course) {
                            course = {
                                name: name,
                                teacher: teachers.length ? teacherKey : null,
                                note: null,
                                blocks: [],
                                seen: {}
                            };
                            bucket['@' + teacherKey] = course;
                            courses.push(course);
                        }
                        if (usedLabel) labelFilled++;
                        var runs = runsOf(weeks);
                        for (var n = 0; n < runs.length; n++) {
                            var run = runs[n];
                            var block = {
                                dayOfWeek: d,
                                startPeriod: periods.start,
                                endPeriod: periods.end,
                                startWeek: run.start,
                                endWeek: run.end,
                                weekType: run.weekType,
                                location: rooms.length ? rooms[0] : null
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

    if (courses.length === 0) {
        throw new Error(
            '本学期没有解析到任何课程：可能是还没排课，或教务页面改了结构（也可能登录状态已失效）'
        );
    }

    var maxWeek = 0;
    var maxPeriod = 0;
    var outCourses = [];
    for (var ci = 0; ci < courses.length; ci++) {
        var list = courses[ci].blocks;
        for (var bi = 0; bi < list.length; bi++) {
            if (list[bi].endWeek > maxWeek) maxWeek = list[bi].endWeek;
            if (list[bi].endPeriod > maxPeriod) maxPeriod = list[bi].endPeriod;
        }
        // 只把该进载荷的字段交出去：seen 是解析时的去重台账，不进载荷
        outCourses.push({
            name: courses[ci].name,
            teacher: courses[ci].teacher,
            note: courses[ci].note,
            blocks: list
        });
    }

    // ⑥ 总周数：教务不给这个值。课表里出现更晚的周次就以它为准（否则载荷校验会判 endWeek 越界），
    // 并如实说明 —— 它和教务真值在库里长得一模一样。
    var totalWeeks = maxWeek > DEFAULT_TOTAL_WEEKS ? maxWeek : DEFAULT_TOTAL_WEEKS;
    var raisedTotalWeeks = totalWeeks > DEFAULT_TOTAL_WEEKS;

    // ⑤ 作息表覆盖：内置表是 1..14 连续的，连堂（比如 [01-04节]）每一节都有时间。
    // 课表用到表外的节次时列出来（不猜时间），说清楚这几节不会显示上下课时间。
    var beyondPeriods = [];
    for (var period = PERIOD_TIMES.length + 1; period <= maxPeriod && beyondPeriods.length < 8; period++) {
        beyondPeriods.push(period);
    }
    var beyondMore = maxPeriod > PERIOD_TIMES.length + beyondPeriods.length;

    var termName = clean(term.name) || termNameFromCode(term.code);

    // extract.js 一定会带 now（提取时刻）；缺了就没法推算开学日，明确报错而不是瞎猜一个日期
    var firstDay = mondayOf(data.now);
    if (!firstDay) {
        throw new Error('提取数据里缺少 now（提取时刻），无法推算开学日期');
    }

    // 推算/假定出来的东西逐条说清楚（§4.2）：这些值在库里和真值长得一模一样，
    // 不说明用户就没有机会发现「现在第几周」是错的。
    var warnings = [
        '开学日期无法从教务获取，已按最近的周一（' + firstDay + '）推算，请在「学期管理」里核对',
        '教务页面不提供学期总周数与作息时间：总周数按 ' + totalWeeks + ' 周、' +
            '节次时间按适配器内置的 14 节设定，请在「学期管理」里核对'
    ];
    if (raisedTotalWeeks) {
        warnings.push('课表里有第 ' + totalWeeks + ' 周的课：学期总周数已按它给到 ' + totalWeeks +
            ' 周（教务不给总周数），请核对是不是真实周次');
    }
    if (droppedWeeks > 0) {
        warnings.push('有 ' + droppedWeeks + ' 条周次超出 ' + MAX_WEEK + ' 周，已按脏数据丢弃');
    }
    if (labelFilled > 0) {
        warnings.push('有 ' + labelFilled + ' 个课程块页面没给节次（方括号里没有节次），' +
            '已按所在行的「第N节」标签补上，请核对');
    }
    var skippedTotal = noNameBlocks + noSpecBlocks + noWeekBlocks + noPeriodBlocks;
    if (skippedTotal > 0) {
        var why = [];
        if (noNameBlocks) why.push('缺课程名 ' + noNameBlocks + ' 个');
        if (noSpecBlocks) why.push('缺周次节次 ' + noSpecBlocks + ' 个');
        if (noWeekBlocks) why.push('缺周次 ' + noWeekBlocks + ' 个');
        if (noPeriodBlocks) why.push('缺节次 ' + noPeriodBlocks + ' 个');
        warnings.push('有 ' + skippedTotal + ' 个课程块没能解析出来（' + why.join('、') +
            '），已跳过：教务页面结构可能已调整，欢迎反馈给适配器');
    }
    if (beyondPeriods.length) {
        warnings.push('课表里用到了内置作息表没有的节次（第 ' + beyondPeriods.join('、') +
            (beyondMore ? ' 等' : '') + ' 节）：这几节在课表里不会显示上下课时间，请核对');
    }
    if (bestLabels < 4) {
        warnings.push('课表里没找到星期表头，已按每行最后 7 列对应周一到周日，请核对导入结果');
    } else if (shortRows > 0) {
        warnings.push('有 ' + shortRows + ' 行的格子数比表头少（行首的节次格子被合并到了上一行），' +
            '已按该行最后 7 列对应周一到周日对齐，请核对这几天的课');
    }
    if (!termName) {
        termName = '淮北理工学院课表';
        warnings.push('没能识别出学年学期名称，学期名已用「淮北理工学院课表」占位，请在「学期管理」里改名');
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
                periodTimes: PERIOD_TIMES,
                courses: outCourses
            }
        ]
    });
})()
