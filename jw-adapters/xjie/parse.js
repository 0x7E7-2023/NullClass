(function () {
    // 新疆工程学院（强智 · 高校综合管理教务系统，学生端 /jsxsd/）课表解析
    // 移植自 shiguang_warehouse 的 XJIE/xjie_01.js（MIT，作者 星河欲转）
    //   https://github.com/XingHeYuZhuan/shiguang_warehouse
    // 上游的核心是 parseTimetableToModel() + parseWeeks()：读 #timetable 里每个格子
    // div.kbcontent 的明细，按 font[title=教师|周次(节次)|教室] 取字段；周次文本形如
    // "1-9,11-17(周)[01-02节]" / "12-15(周)"（上游注释里的原话）。
    // 它与已移植的 hynu 是同平台近克隆：逐行比对只有四处不同（文件头注释、教师前缀、
    // 作息表、请求主机），但**同平台不等于同一套编码**，上游的坑一个都没少，逐条改掉：
    //   ① 上游 parseWeeks 先 split('(')[0]：单/双写在括号里时会被整段丢掉，
    //      "1-16周(双)" 退化成每周都上（hynu 第一批就踩过这条）——
    //      这里认括号内外的单/双/单双周，按标记过滤后再切 ODD / EVEN 段，并进 warnings；
    //   ② 括号里的纯数字（如 "(1)"）是序号不是周次，不当周次用（当成周次会把同一门课的
    //      真实周次一起吃掉），认到就计数进 warnings；整条周次只有序号的块跳过并出声；
    //   ③ 上游节次只认 /\[(\d+)(?:-(\d+))?节\]/：连堂的 "[03-04-05节]"（同平台的 BTBU
    //      注释里有这种写法）、"[0102节]" 都读不出来，而这些块上游是**直接丢**的
    //      （它最后一句是 if (name && weekStr && start > 0)）。这里会认，认不出就回落
    //      所在行的节次标签，再不行计数进 warnings —— 不静默丢课；
    //   ④ 上游按「第几个格子就是星期几」硬算星期（cells.forEach((cell, dayIndex))），
    //      课表首列是节次列时整表错一天，这里按表头的星期标签对列号（hynu 也改了这条）；
    //   ⑤ 作息表（上游写死 11 节）覆盖课表里用到的每一节：超出内置表的节次进 warnings，
    //      不替教务猜时间；
    //   ⑥ 总周数被课表里更晚的周次抬高时要出声（上游写死 20，超了也不说）。
    //
    // 输入是 extract.js 交出来的原始结构：
    //   { now: "2026-09-12", term: { code, name, source }, rows: [ [ { text, parts: [原始 HTML] }, … ], … ] }
    // 这里不碰 DOM（CI 的 Rhino 里没有），全部按字符串 + 正则处理，是纯函数。
    var data = JSON.parse(__ncInput);
    var rows = data.rows || [];
    var term = data.term || {};

    // 学校作息时间（教务处 11 节）。上游硬编码在 saveAppTimeSlots() 里，逐条照搬：
    // 新疆用北京时间，但作息整体后移，第一节是 10:00。
    var PERIOD_TIMES = [
        { periodIndex: 1, start: '10:00', end: '10:45' },
        { periodIndex: 2, start: '10:55', end: '11:40' },
        { periodIndex: 3, start: '11:55', end: '12:40' },
        { periodIndex: 4, start: '12:50', end: '13:35' },
        { periodIndex: 5, start: '13:45', end: '14:30' },
        { periodIndex: 6, start: '16:00', end: '16:45' },
        { periodIndex: 7, start: '16:55', end: '17:40' },
        { periodIndex: 8, start: '17:55', end: '18:40' },
        { periodIndex: 9, start: '18:50', end: '19:35' },
        { periodIndex: 10, start: '19:50', end: '20:35' },
        { periodIndex: 11, start: '20:45', end: '21:30' }
    ];

    var DEFAULT_TOTAL_WEEKS = 20;   // 上游写死的学期总周数（教务页面不给这个值）
    var MAX_WEEK = 30;              // 载荷校验的周次上限（1..30），超出的按脏数据丢弃
    var MAX_PERIOD = 30;            // 节次的合理范围，读出来更大的按脏数据算
    var MAX_WARNINGS = 20;          // 载荷校验的条数上限，给满了就不再往里塞
    // 课程合并键的分隔符：运行期生成这个不可见字符，源码里**不写转义序列**
    //（本仓库的工具链会把那种转义序列落成真控制字符，文件会被 grep 当成二进制）。
    var KEY_SEP = String.fromCharCode(0);
    var DASHES = /-{5,}/;           // 一格放多门课时的分隔线（上游写死 21/22 个减号，这里放宽）
    var TEACHER_TITLE = /^(教师|老师|任课教师|授课教师|教师姓名)$/;
    var ROOM_TITLE = /^(教室|上课地点|上课教室|地点|教室名称)$/;
    var COURSE_TITLE = /^(课程|课程名称|课程名|科目)$/;
    var SPEC_TITLE = /周次|节次/;   // 别把「时间」并进来：标着「上课时间」的 font 内容是钟点（11:10-11:50），
    // 会被下面的区间正则读成第 10-11 周，凭空造出排课。上游只查固定 title，这个宽匹配是移植时自己加的。
    var TEACHER_LABEL = /^(任课教师|授课教师|教师|老师)\s*[:：]\s*/;
    var ROOM_LABEL = /^(上课地点|上课教室|教室|地点)\s*[:：]\s*/;
    var PLACEHOLDER = /^(未知|未知教师|未知教室|未知地点|待定|暂无|无)$/;

    var warnings = [];

    // 解析过程中攒下来的计数与样例：哪些块被跳过了必须说得出来（手册：不许静默丢数据）
    var stats = {
        parity: 0,            // 带单/双标记的周次段
        bothParity: 0,        // 写成「单双周」（每周都上）的
        serial: 0,            // 当序号忽略掉的纯数字括号组
        serialOnly: 0,        // 整条周次只有序号的块
        unreadableWeeks: 0,   // 没读懂的周次段
        rowFallback: 0,       // 节次回落到所在行的标签
        noWeeks: 0,           // 读不出周次的块
        noPeriods: 0,         // 读不出节次的块
        noSpec: 0,            // 连周次/节次字段都没有的块
        noName: 0,            // 读不出课名的块
        droppedWeeks: 0,      // 超出上限被丢弃的周次
        sample: ''            // 一个读不出来的原始文本，给 warnings 当例子
    };

    function warn(message) {
        if (warnings.length >= MAX_WARNINGS) return;
        if (warnings.indexOf(message) >= 0) return;
        warnings.push(message);
    }

    function clean(value) {
        if (value === null || value === undefined) return '';
        return String(value).replace(/\s+/g, ' ').trim();
    }

    function clip(value, max) {
        var text = clean(value);
        if (text.length <= max) return text;
        return text.substring(0, max) + '…';
    }

    function noteSample(value) {
        if (stats.sample) return;
        stats.sample = clip(value, 30);
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

    // 取某一类 font 的文本：上游把教师写成「任课教师:张三」（它只 replace 了「任课教师:」这一种），
    // 这里把常见前缀都去掉；去完只剩「未知 / 待定」这类占位符的按**空**处理 ——
    // 「未知」会被当成真姓名、真地点显示出来，空着更好（手册 §4.7）。
    function labelTexts(fonts, titleRe, labelRe) {
        var out = [];
        for (var i = 0; i < fonts.length; i++) {
            if (!titleRe.test(fonts[i].title)) continue;
            var text = clean(fonts[i].text).replace(labelRe, '');
            if (!text || PLACEHOLDER.test(text)) continue;
            if (out.indexOf(text) < 0) out.push(text);
        }
        return out;
    }

    // "周次(节次)" 一条 font 里既有周次也有节次（上游这所学校就是），有的强智学校拆成
    // 「周次」与「节次」两条 font —— 两种都按「方括号前是周次、方括号里是节次」切。
    function splitSpec(value) {
        var text = clean(value);
        var bracket = /\[([^\]]*)\]/.exec(text);
        if (!bracket) return { weeks: text, periods: '' };
        return {
            weeks: clean(text.substring(0, bracket.index)),
            periods: clean(bracket[1])
        };
    }

    // 把「周次」「节次」两类 font 配成一条排课记录：一条合写的（周次(节次)）直接成对，
    // 拆写的（周次 + 节次）相邻两条配成一对，顺序反过来也认。
    function specsOf(blockHtml) {
        var fonts = fontsOf(blockHtml);
        var specs = [];
        var pending = null;
        for (var i = 0; i < fonts.length; i++) {
            if (!SPEC_TITLE.test(fonts[i].title)) continue;
            var split = splitSpec(fonts[i].text);
            if (!split.weeks && !split.periods) continue;
            if (split.weeks && split.periods) {
                if (pending) {
                    specs.push(pending);
                    pending = null;
                }
                specs.push(split);
                continue;
            }
            if (split.weeks) {
                if (pending) specs.push(pending);
                pending = split;
                continue;
            }
            if (pending) {
                pending.periods = split.periods;
                specs.push(pending);
                pending = null;
            } else {
                specs.push({ weeks: '', periods: split.periods });
            }
        }
        if (pending) specs.push(pending);
        return specs;
    }

    function pushWeek(out, week, parity) {
        if (!(week >= 1)) return;
        if (week > MAX_WEEK) {
            stats.droppedWeeks++;
            return;
        }
        if (parity === '单' && week % 2 === 0) return;
        if (parity === '双' && week % 2 === 1) return;
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

    // 周次文本 → 周次数组。强智的写法（上游注释 + 同平台 BTBU 的注释）：
    //   "1-9,11-17(周)[01-02节]"、"12-15(周)"、"1-8,10-16(周)[03-04节]"、
    //   "1-15周(单)[01-02节]"、"2-16(双)[01-02节]"、"9(周)"
    // 单/双可能在「周」字前面、后面或括号里（"1-16周(双)" 就是写在后面那种），都要认出来；
    // 括号里只可能是 周 / 单 / 双，出现纯数字（"(1)"）时那是序号，不是周次。
    // 单/双 标记可能写在「周」字后面（"1-16周(双)"）、括号里（"2-16(双)"）、
    // 整段开头（"双周2-6、10"）或在某一段里（"1-8周(单),9-16周(双)" 只有后半段是双周）。
    // 最后那种写法必须**按段**认：整串一起判会把前半段也算成双周，整张课表都错。
    // 上游更彻底：split('(')[0] 直接把标记连括号丢掉，两段都变成每周都上。
    function weeksIn(source) {
        var raw = clean(source);
        var bothParity = /单双周|双单周/.test(raw);
        if (bothParity) stats.bothParity++;
        var globalParity = '';
        var head = /^\s*(单|双)\s*周?/.exec(raw);
        if (!bothParity && head) {
            globalParity = head[1];
            raw = raw.substring(head[0].length);
        }
        var serialHere = false;
        var weeks = [];
        var segments = raw.split(/[,，、;；]/);
        for (var i = 0; i < segments.length; i++) {
            if (!clean(segments[i])) continue;
            var parity = globalParity;
            var segmentSerial = false;
            // 括号里只可能是 周 / 单 / 双，或者整段周次；出现纯数字（"(1)"）时那是序号，
            // 不是周次 —— 当成周次会把同一门课的真实周次一起吃掉。
            var inner = segments[i].replace(/[（(]([^）)]*)[）)]/g, function (whole, text) {
                var body = clean(text);
                if (/^\d{1,3}(\s*[-—~至]\s*\d{1,3})+$/.test(body)) return ' ' + body + ' ';
                if (/^\d{1,3}$/.test(body)) {
                    segmentSerial = true;
                    serialHere = true;
                    stats.serial++;
                    return ' ';
                }
                if (!bothParity && body.indexOf('单') >= 0 && body.indexOf('双') < 0) parity = '单';
                else if (!bothParity && body.indexOf('双') >= 0 && body.indexOf('单') < 0) parity = '双';
                return ' ';
            });
            if (!bothParity && inner.indexOf('双') >= 0 && inner.indexOf('单') < 0) parity = '双';
            else if (!bothParity && inner.indexOf('单') >= 0 && inner.indexOf('双') < 0) parity = '单';
            if (parity === '单' || parity === '双') stats.parity++;
            var body = inner.replace(/\[[^\]]*\]/g, ' ').replace(/[第周]/g, ' ').replace(/[至到]/g, '-');
            var parts = body.split(/\s+/);
            var matched = false;
            for (var p = 0; p < parts.length; p++) {
                var part = parts[p];
                if (!part) continue;
                var range = /^(\d{1,2})\s*[-—~]\s*(\d{1,2})$/.exec(part);
                if (range) {
                    var start = parseInt(range[1], 10);
                    var end = parseInt(range[2], 10);
                    for (var w = start; w <= end; w++) pushWeek(weeks, w, parity);
                    matched = true;
                    continue;
                }
                if (/^\d{1,2}$/.test(part)) {
                    pushWeek(weeks, parseInt(part, 10), parity);
                    matched = true;
                }
            }
            if (!matched && !segmentSerial) {
                stats.unreadableWeeks++;
                noteSample(raw);
            }
        }
        if (!weeks.length && serialHere) stats.serialOnly++;
        return uniqueSorted(weeks);
    }

    // 节次文本 → {start, end}。方括号里是节次，强智的写法：
    //   "01-02节"（连堂）、"03-04-05节"（同平台 BTBU 注释里的三小节连排）、
    //   "0102节" / "030405节"（两位一节连着写）、"9" / "12节"（单节）。
    // 认不出来返回 null —— 调用方要么回落这一行的节次标签，要么计数进 warnings，不许静默丢课。
    function periodsIn(source) {
        var body = clean(source).replace(/[第节\s]/g, '').replace(/[（）()]/g, '');
        if (!body) return null;
        var start = null;
        var end = null;
        function note(value) {
            if (!(value >= 1) || value > MAX_PERIOD) return;
            if (start === null || value < start) start = value;
            if (end === null || value > end) end = value;
        }
        var segments = body.split(/[,，、]/);
        for (var i = 0; i < segments.length; i++) {
            var segment = segments[i];
            if (!segment) continue;
            if (/^\d+$/.test(segment)) {
                // 四位以上且偶数个数字是「两位一节」连着写（0102 = 1-2 节），否则当一节读
                if (segment.length >= 4 && segment.length % 2 === 0) {
                    for (var k = 0; k < segment.length; k += 2) {
                        note(parseInt(segment.substring(k, k + 2), 10));
                    }
                } else {
                    note(parseInt(segment, 10));
                }
                continue;
            }
            if (!/^\d{1,3}([-—~至]\d{1,3})+$/.test(segment)) return null;
            var nums = segment.split(/[-—~至]/);
            for (var n = 0; n < nums.length; n++) note(parseInt(nums[n], 10));
        }
        if (start === null || end === null) return null;
        return { start: start, end: end };
    }

    // 周次集合 → 极大段（手册 §4.1）：步长 1 视作每周，步长 2 视作单/双周
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

    // 星期表头：找「没有课程内容、且有 ≥4 个星期标签」的那一行，按它的列号取每天那一列。
    // 上游把「第几个格子」直接当星期几，课表首列是节次列时整张表会错一天。
    var dayColumns = [-1, -1, -1, -1, -1, -1, -1];
    var headerRow = -1;
    var bestLabels = 0;
    for (var r = 0; r < rows.length; r++) {
        var hasContent = false;
        var found = [-1, -1, -1, -1, -1, -1, -1];
        var labels = 0;
        for (var c = 0; c < rows[r].length; c++) {
            if (rows[r][c].parts && rows[r][c].parts.length) hasContent = true;
            var day = dayOfLabel(rows[r][c].text);
            if (day >= 1 && day <= 7 && found[day - 1] < 0) {
                found[day - 1] = c;
                labels++;
            }
        }
        if (hasContent || labels < 4) continue;
        if (labels > bestLabels) {
            bestLabels = labels;
            headerRow = r;
            dayColumns = found;
        }
    }
    var columnFallback = bestLabels < 4;

    function isDayColumn(col) {
        for (var i = 0; i < 7; i++) {
            if (dayColumns[i] === col) return true;
        }
        return false;
    }

    function columnOfDay(row, day) {
        if (!columnFallback) return dayColumns[day - 1];
        // 没认出表头时的兜底：多于 7 列就按「最后 7 列是周一到周日」算
        //（首列是节次列是最常见的多出那一列）
        var offset = row.length > 7 ? row.length - 7 : 0;
        return offset + day - 1;
    }

    // 这一行的「节次」标签（强智是行首那一列，如「第1-2节」）。有的明细只写周次不写节次
    //（上游注释里的 "12-15(周)" 就是这种），那一行的标签就是它的回落值；不回落的话
    // 上游会把这些课整块丢掉。
    function rowPeriodsOf(row) {
        if (!row.length) return null;
        if (columnFallback) return periodsIn(row[0].text);
        for (var c = 0; c < row.length; c++) {
            if (isDayColumn(c)) continue;
            var got = periodsIn(row[c].text);
            if (got) return got;
        }
        return null;
    }

    var order = [];
    var byCourse = {};

    for (var ri = 0; ri < rows.length; ri++) {
        if (ri === headerRow) continue;
        var row = rows[ri];
        var rowPeriods = rowPeriodsOf(row);
        for (var d = 1; d <= 7; d++) {
            var col = columnOfDay(row, d);
            if (col < 0 || col >= row.length) continue;
            var parts = row[col].parts || [];
            for (var p = 0; p < parts.length; p++) {
                var blocks = String(parts[p]).split(DASHES);
                for (var b = 0; b < blocks.length; b++) {
                    var blockHtml = blocks[b];
                    if (plainText(blockHtml) === '') continue;
                    var name = nameOf(blockHtml);
                    if (!name) {
                        stats.noName++;
                        noteSample(plainText(blockHtml));
                        continue;
                    }
                    var fonts = fontsOf(blockHtml);
                    var teachers = labelTexts(fonts, TEACHER_TITLE, TEACHER_LABEL);
                    var rooms = labelTexts(fonts, ROOM_TITLE, ROOM_LABEL);
                    var specs = specsOf(blockHtml);
                    if (!specs.length) {
                        stats.noSpec++;
                        noteSample(name);
                        continue;
                    }
                    var key = name + KEY_SEP + teachers.join(',');
                    for (var s = 0; s < specs.length; s++) {
                        var weeks = weeksIn(specs[s].weeks);
                        var periods = periodsIn(specs[s].periods);
                        if (!periods && rowPeriods) {
                            periods = rowPeriods;
                            stats.rowFallback++;
                        }
                        if (!weeks.length || !periods) {
                            if (!weeks.length) stats.noWeeks++;
                            else stats.noPeriods++;
                            noteSample(name);
                            continue;
                        }
                        // 课程要等真产出一条安排才登记：周次/节次解析不出来的块只进 warnings，
                        // 不能在课表里留下一门「没有任何安排」的空课。
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
                        var course = byCourse[key];
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
                            var blockKey = [
                                block.dayOfWeek, block.startPeriod, block.endPeriod,
                                block.startWeek, block.endWeek, block.weekType,
                                block.location || ''
                            ].join('|');
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
        // 一个块都没解析出来时不能说「没排课」了事：把块级的失败计数报出来，
        // 用户和运维才看得出是页面结构变了还是真没排课。
        var blocked = stats.noWeeks + stats.noPeriods + stats.noSpec + stats.noName;
        if (blocked > 0) {
            throw new Error('课表里有 ' + blocked + ' 个课程块，一个都没能解析出来（' +
                (stats.sample ? '如「' + stats.sample + '」' : '格式与预期不符') +
                '）：教务页面结构可能已调整，欢迎反馈');
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
        courses.push({ name: item.name, teacher: item.teacher, note: item.note, blocks: item.blocks });
    }

    var totalWeeks = maxWeek > DEFAULT_TOTAL_WEEKS ? maxWeek : DEFAULT_TOTAL_WEEKS;
    if (totalWeeks > MAX_WEEK) totalWeeks = MAX_WEEK;
    if (maxPeriod < PERIOD_TIMES.length) maxPeriod = PERIOD_TIMES.length;

    var termName = clean(term.name) || termNameFromCode(term.code);
    if (!termName) {
        termName = '新疆工程学院课表';
    }

    // extract.js 一定会带 now（提取时刻）；缺了就没法推算开学日，明确报错而不是瞎猜一个日期
    var firstDay = mondayOf(data.now);
    if (!firstDay) {
        throw new Error('提取数据里缺少 now（提取时刻），无法推算开学日期');
    }

    // 推算/假定出来的东西逐条说清楚（手册 §4.2）：这些值在库里和真值长得一模一样，
    // 不说明用户就没有机会发现「现在第几周」是错的。
    warn('开学日期无法从教务获取，已按最近的周一（' + firstDay + '）推算，请在「学期管理」里核对');
    if (!clean(term.name) && !termNameFromCode(term.code)) {
        warn('没能识别出学年学期名称，学期名已用「新疆工程学院课表」占位，请在「学期管理」里改名');
    } else if (clean(term.source) === 'select-first') {
        warn('教务页面的「学年学期」下拉框没有标记选中项，已按第一项（' + clean(term.code) + '）提取，请在页面上确认学期');
    } else if (clean(term.source) === 'text') {
        warn('学年学期是从页面文字里认出来的（' + clean(term.code) + '），请在「学期管理」里核对');
    }
    warn('教务页面不提供学期总周数与作息时间：总周数按 ' + totalWeeks + ' 周、' +
        '节次时间按适配器内置的 ' + PERIOD_TIMES.length + ' 节设定，请在「学期管理」里核对');

    if (stats.parity > 0) {
        warn('课表里有 ' + stats.parity + ' 处周次带「单」或「双」标记（如 1-16周(双)），' +
            '已按标记只保留单周或双周，如与该课的实际安排不符请反馈');
    }
    if (stats.bothParity > 0) {
        warn('课表里有 ' + stats.bothParity + ' 处周次写成「单双周」，按每周都上处理，' +
            '如与该课的实际安排不符请反馈');
    }
    if (stats.serial > 0) {
        warn('课表里有 ' + stats.serial + ' 处括号里只有数字（如 (1)）：那是序号不是周次，已忽略');
    }
    if (stats.serialOnly > 0) {
        warn('有 ' + stats.serialOnly + ' 个课程块的周次只写了括号里的序号（如 (1)），没法当周次用，' +
            '已跳过；欢迎反馈这门课的原始写法');
    }
    if (stats.unreadableWeeks > 0) {
        warn('有 ' + stats.unreadableWeeks + ' 段周次没能读懂（如「' + stats.sample + '」），已跳过，请核对课表');
    }
    if (stats.rowFallback > 0) {
        warn('有 ' + stats.rowFallback + ' 处排课的节次教务没写（如只有「12-15(周)」），' +
            '已按所在行的节次标签放入，请核对节次');
    }
    if (stats.noWeeks || stats.noPeriods || stats.noSpec || stats.noName) {
        var detail = [];
        if (stats.noWeeks) detail.push('周次读不出 ' + stats.noWeeks + ' 个');
        if (stats.noPeriods) detail.push('节次读不出 ' + stats.noPeriods + ' 个');
        if (stats.noName) detail.push('没有课名 ' + stats.noName + ' 个');
        if (stats.noSpec) detail.push('没有周次/节次字段 ' + stats.noSpec + ' 个');
        warn('课表里有 ' + (stats.noWeeks + stats.noPeriods + stats.noSpec + stats.noName) +
            ' 个课程块没能解析（' + detail.join('、') + '），已跳过' +
            (stats.sample ? '（如「' + stats.sample + '」）' : '') + '，欢迎反馈');
    }
    if (columnFallback) {
        warn('课表里没找到星期表头，已按每行最后 7 列对应周一到周日，请核对导入结果');
    }
    if (maxWeek > DEFAULT_TOTAL_WEEKS) {
        warn('课表里最晚的课在第 ' + maxWeek + ' 周，学期总周数已从内置的 ' + DEFAULT_TOTAL_WEEKS +
            ' 周抬高到 ' + totalWeeks + ' 周，请在「学期管理」里核对');
    }
    if (maxPeriod > PERIOD_TIMES.length) {
        warn('课表里用到了第 ' + maxPeriod + ' 节，而内置作息表只有 ' + PERIOD_TIMES.length +
            ' 节：这几节在课表里不会显示上下课时间，请在「节次设置」里补上');
    }
    if (stats.droppedWeeks > 0) {
        warn('有 ' + stats.droppedWeeks + ' 条周次超出 ' + MAX_WEEK + ' 周，已按脏数据丢弃');
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
                courses: courses
            }
        ]
    });
})()
