(function () {
    // 东北大学秦皇岛分校 教务适配器（树维 EAMS 平台，jwxt.neuq.edu.cn/eams）—— 第二步：纯转换。
    //
    // 移植自 shiguang_warehouse 的 NEUQ/neuq.js
    //   https://github.com/XingHeYuZhuan/shiguang_warehouse  （MIT，作者 aryunm）
    //   上游快照 e62554a4034386b893bcd6813c7b2b64f8c730a3（2026-09-12）
    //
    // ⚠️ 这不是东北大学主校区的适配器。主校区（「jwxt.neu.edu.cn」）走**金智 jwapp**，
    //    我们已合并的「jw-adapters/neu/」是那一套；秦皇岛分校走**树维 EAMS**，
    //    两套系统只是名字像，取数链路、周次编码、作息表都不一样，**别混用**。
    //    「/eams/」是上海树维信息科技有限公司（SupWisdom，新开普子公司）的产品，
    //    不是湖南强智科技（强智走「/jsxsd/」）。
    //
    // 上游在同一段脚本里取数、算周次、拼课程；这里只做纯转换（不碰页面、不发请求，CI 用 Rhino 实跑），
    // 输入是 extract.js 交出来的原始数据：探测页 HTML、学期列表（已解析成字段）、课表 HTML 原文。
    //
    // 移植改动（按移植手册 §4 与本批检查表逐条对照）：
    //   ① ES6 → ES5（去掉模板串、箭头函数、块级声明关键字、扩展运算符、Function 构造器）
    //   ② 上游用「Function("return (" + raw + ")")()」解析学期响应（命中手册 §5 第 6 条：
    //      远程取回的字符串进了 Function 构造器）。这里连原始响应都不进来 —— extract.js 已经
    //      用「花括号配平 + 键值对」把它读成字段数组，本文件只做归一化，不执行任何取回来的代码
    //   ③ 上游用「showSingleSelection」让用户选学期：改成**自动取当前学期**
    //      （探测页里的「semesterBar…Semester」的 value → 「var semesterIndex」 → 当前日期）
    //   ④ 周次位图按**本批统一口径**读：「bitmap[i] === '1'」且「i >= 1」 → 第 i 周；
    //      第 0 位是占位符，为「1」时不产出「第 0 周」而是写进 warnings。
    //      上游原文是「for (i = 0; …) if (bitmap[i] === '1') weeks.push(i)」，没有跳过 0 位
    //   ⑤ 开学日：上游不管。这里先用 semesterCalendar 里该学期的起止日期推第 1 周，
    //      推不出来就按「学年 + 学期」或当前日期推最近的那个开学季（回退到那一周的周一，
    //      手册 §4.3），**推算值一律进 warnings**
    //   ⑥ 总周数：上游不管。semesterCalendar 的起止日期能算出周数就用它，否则用内置 20 周；
    //      课表里出现更晚的周次时抬高总周数（否则那几周的课放不下、整包会被校验拒掉）
    //   ⑦ 教师 / 教室拿不到就**留空**（上游写「""」，另外上游还会把「join(...)」表达式原样当教师名）
    //   ⑧ 「unitCount」读不到时用缺省 12，并**同时**写进 warnings（节次数是猜的，上册检查表第 4 条）
    //   ⑨ 作息时间用的是上游脚本内置的那张 12 节表（脚本作者对学校的了解，没有向教务核对过），
    //      写进载荷的同时进 warnings
    //   ⑩ 周次超 30 周一律 clamp 到 30 并 warn（载荷校验 totalWeeks ∈ 1..30）
    //
    // ⚠️ 内存：本文件（与 extract.js）的注释、正则、字符串里**一律不出现**字面 NUL 字节，
    //    也不出现 unicode 转义序列（某种编辑工具会把转义序列落成真的控制字符，让文件变二进制）。
    //    复合键分隔符用 String.fromCharCode(0) 取。自检：文件里 NUL 字节数必须是 0。

    var data = JSON.parse(typeof __ncInput === 'undefined' ? '{}' : __ncInput);

    var SEP = String.fromCharCode(0);

    var MAX_TOTAL_WEEKS = 30;       // 载荷校验：totalWeeks ∈ 1..30
    var MAX_WARNINGS = 20;          // 载荷校验：warnings ≤ 20 条
    var MAX_WARNING_CHARS = 200;    // 载荷校验：每条 ≤ 200 字
    var DEFAULT_UNIT_COUNT = 12;    // 上游 neuq.js 的缺省值（读不到时用它，并 warn）
    var FALLBACK_TOTAL_WEEKS = 20;  // 上游不带总周数；教务给不出时按常见的 20 周
    var SCHOOL_NAME = '东北大学秦皇岛分校';

    // 该校作息（12 节）：出自上游 NEUQ/neuq.js 的 getPresetTimeSlots()，原样搬过来。
    // 上游没有向教务请求作息，这张表就是脚本作者对学校的了解 —— 它是**适配器自带的值**，
    // 载荷里带出去的同时必须写进 warnings 说明它没跟教务核对过（真机核对时请对照教务处公布的作息）。
    var PERIOD_TIMES = [
        { periodIndex: 1, start: '08:00', end: '08:45' },
        { periodIndex: 2, start: '08:50', end: '09:35' },
        { periodIndex: 3, start: '10:05', end: '10:50' },
        { periodIndex: 4, start: '10:55', end: '11:40' },
        { periodIndex: 5, start: '14:00', end: '14:45' },
        { periodIndex: 6, start: '14:50', end: '15:35' },
        { periodIndex: 7, start: '16:05', end: '16:50' },
        { periodIndex: 8, start: '16:55', end: '17:40' },
        { periodIndex: 9, start: '18:40', end: '19:25' },
        { periodIndex: 10, start: '19:30', end: '20:15' },
        { periodIndex: 11, start: '20:25', end: '21:10' },
        { periodIndex: 12, start: '21:15', end: '22:00' }
    ];

    // ---------- 小工具 ----------
    function text(value) {
        if (value === null || value === undefined) return '';
        return String(value).replace(/\s+/g, ' ').replace(/^\s+|\s+$/g, '');
    }

    function trimStr(value) {
        return String(value === null || value === undefined ? '' : value).replace(/^\s+|\s+$/g, '');
    }

    function intOf(value) {
        if (value === null || value === undefined || value === '') return null;
        var n = parseInt(value, 10);
        return isNaN(n) ? null : n;
    }

    function pad2(n) {
        return (n < 10 ? '0' : '') + n;
    }

    function isoOfDate(date) {
        return date.getUTCFullYear() + '-' + pad2(date.getUTCMonth() + 1) + '-' + pad2(date.getUTCDate());
    }

    // 该日期所在周的周一（用 UTC 算，避免时区把日期挪一天）。firstDayOfWeek 缺省 1（周一）。
    function mondayOnOrBefore(year, month, day) {
        var date = new Date(Date.UTC(year, month - 1, day));
        var offset = (date.getUTCDay() + 6) % 7;
        return new Date(date.getTime() - offset * 86400000);
    }

    function mondayOfYmd(year, month, day) {
        return isoOfDate(mondayOnOrBefore(year, month, day));
    }

    // 任意形态的日期 → ISO 日期。先按开头认（"2026-09-07～2027-01-10" 这种区间取前一半），
    // 认不到再在串里找（"第1周 2026-09-07" / "2026-09-07 至 2027-01-10" 这类）。
    function isoOfAny(value) {
        var s = text(value);
        if (!s) return null;
        var m = /^([0-9]{4})[-/.]([0-9]{1,2})[-/.]([0-9]{1,2})/.exec(s);
        if (!m) m = /([0-9]{4})[-/.]([0-9]{1,2})[-/.]([0-9]{1,2})/.exec(s);
        if (!m) return null;
        var month = parseInt(m[2], 10);
        var day = parseInt(m[3], 10);
        if (month < 1 || month > 12 || day < 1 || day > 31) return null;
        return m[1] + '-' + pad2(month) + '-' + pad2(day);
    }

    // 学期对象里的日期字段形态不明（字符串 / 毫秒时间戳 / 嵌套对象），逐个字段探。
    function isoInValue(value, depth) {
        if (depth > 3 || value === null || value === undefined) return null;
        if (typeof value === 'string') return isoOfAny(value);
        if (typeof value === 'number') {
            if (value > 100000000000 && value < 4000000000000) {
                var date = new Date(value);
                if (!isNaN(date.getTime())) return isoOfDate(date);
            }
            return null;
        }
        if (typeof value === 'object') {
            var keys = Object.keys(value);
            for (var i = 0; i < keys.length; i++) {
                var found = isoInValue(value[keys[i]], depth + 1);
                if (found) return found;
            }
        }
        return null;
    }

    function localTodayIso() {
        var now = new Date();
        return now.getFullYear() + '-' + pad2(now.getMonth() + 1) + '-' + pad2(now.getDate());
    }

    // 按日期推「这个学年该怎么叫」（只在教务连学期名都没给时兜底，推算值一律进 warnings）
    function academicTermName(iso) {
        var year = parseInt(iso.substring(0, 4), 10);
        var month = parseInt(iso.substring(5, 7), 10);
        if (month >= 9) return year + '-' + (year + 1) + '学年第一学期';
        if (month >= 2) return (year - 1) + '-' + year + '学年第二学期';
        return (year - 1) + '-' + year + '学年第一学期';
    }

    // "08:00" / "08:00:00" → "08:00"；认不出来（含 24:00 这种越界）返回 null。
    // 写进载荷的 periodTimes 必须是 00:00-23:59 的 HH:mm，越界会让整个载荷被拒（不是跳过一节）。
    function timeOf(value) {
        var m = /^([01]?[0-9]|2[0-3]):([0-5][0-9])/.exec(text(value));
        if (!m) return null;
        return (m[1].length < 2 ? '0' + m[1] : m[1]) + ':' + m[2];
    }

    function minutesOf(hhmm) {
        return parseInt(hhmm.substring(0, 2), 10) * 60 + parseInt(hhmm.substring(3, 5), 10);
    }

    function cmpStr(a, b) {
        if (a === b) return 0;
        return a < b ? -1 : 1;
    }

    function cmpNum(a, b) {
        return a === b ? 0 : (a < b ? -1 : 1);
    }

    // ---------- 输入解包 ----------
    var root = (data && typeof data === 'object') ? data : {};
    var entryHtml = typeof root.entryHtml === 'string' ? root.entryHtml : '';
    var rawSemesters = (root.semesters instanceof Array) ? root.semesters : [];
    var courseHtml = typeof root.courseHtml === 'string' ? root.courseHtml : '';
    var todayIso = isoOfAny(root.today) || localTodayIso();

    var allWarnings = [];

    function warn(message) {
        var line = String(message);
        if (line.length > MAX_WARNING_CHARS) line = line.substring(0, MAX_WARNING_CHARS - 1) + '…';
        allWarnings.push(line);
    }

    // ---------- 探测页参数（正则直读，不执行页面脚本） ----------
    var RE_IDS_FORM = /bg\.form\.addInput\(\s*form\s*,\s*["']ids["']\s*,\s*["']([0-9]+)["']\s*\)/;
    var RE_IDS_PARAM = /["']ids["']\s*[,:=]\s*["']?([0-9]+)/;
    var RE_TAGID = /id=["'](semesterBar[0-9]+Semester)["']/;
    var RE_SEM_INDEX = /var\s+semesterIndex\s*=\s*([0-9]+)\s*;/;

    function firstMatch(source, regexes) {
        for (var i = 0; i < regexes.length; i++) {
            var m = regexes[i].exec(source);
            if (m) return m[1];
        }
        return '';
    }

    function attrValue(html, tagId) {
        if (!html || !tagId) return '';
        var at = html.indexOf('id="' + tagId + '"');
        if (at < 0) at = html.indexOf("id='" + tagId + "'");
        if (at < 0) return '';
        var tagStart = html.lastIndexOf('<', at);
        var tagEnd = html.indexOf('>', at);
        var tag = html.substring(tagStart < 0 ? 0 : tagStart, tagEnd < 0 ? html.length : tagEnd);
        var m = /value=["']?([0-9]+)/.exec(tag);
        return m ? m[1] : '';
    }

    var studentId = firstMatch(entryHtml, [RE_IDS_FORM, RE_IDS_PARAM]);
    var tagId = firstMatch(entryHtml, [RE_TAGID]);
    var currentSemesterId = text(root.currentSemesterId);

    // 课表响应里通常自带同一组参数：探测页没拿到时从课表 HTML 里补
    if (!studentId) studentId = firstMatch(courseHtml, [RE_IDS_FORM, RE_IDS_PARAM]);
    if (!tagId) tagId = firstMatch(courseHtml, [RE_TAGID]);
    if (!currentSemesterId) currentSemesterId = attrValue(entryHtml, tagId) || attrValue(courseHtml, tagId);
    // studentId 只用于「这个学期到底是谁的课表」这一层判断，不进输出、不进 fixture
    if (!studentId) warn('教务页面里没有读到选课身份（ids），学期自动选择只能靠其它线索，请核对导入的学期');

    // ---------- 学期列表（只读字段，不执行任何取回来的代码） ----------
    // extract.js 已经把 semesterCalendar 的响应解成「[{id, schoolYear, term, startDate, endDate}]」
    // 交出来（它负责不执行远程代码地解析那个裸 JS 对象字面量）。这里只做归一化：
    // 缺 id 的条目丢掉，日期认不出来的留空。
    var semesters = [];
    var badSemesters = 0;
    for (var si = 0; si < rawSemesters.length; si++) {
        var rawSem = rawSemesters[si];
        if (!rawSem || typeof rawSem !== 'object') { badSemesters++; continue; }
        var semId = text(rawSem.id);
        if (!semId) { badSemesters++; continue; }
        var startIso = isoOfAny(rawSem.startDate) || isoInValue(rawSem.startDate, 0);
        var endIso = isoOfAny(rawSem.endDate) || isoInValue(rawSem.endDate, 0);
        semesters.push({
            id: semId,
            schoolYear: text(rawSem.schoolYear),
            term: text(rawSem.term),
            startDate: startIso,
            endDate: endIso
        });
    }
    if (badSemesters > 0) {
        warn('教务返回的学期列表里有 ' + badSemesters + ' 条学期没能读懂（缺 id），已跳过，请反馈');
    }
    if (!semesters.length) {
        warn('教务没有给出可用的学期列表，学期名、开学日与总周数都只能推算，请逐项核对');
    }

    // 学期名：教务给的学年 + 「第 N 学期」最准（上游把它拼成 "2026-2027 1学期"，
    // 这里拼成「2026-2027学年第一学期」；手册 §4.7：别用适配器名当学期名）。
    var CN_TERM = { '1': '一', '2': '二', '3': '三', '4': '四', '5': '五' };

    function semesterLabel(termRaw) {
        var t = text(termRaw);
        if (!t) return '';
        if (t.indexOf('学期') >= 0) return t;
        if (CN_TERM[t]) return '第' + CN_TERM[t] + '学期';
        return '第' + t + '学期';
    }

    function semesterNameOf(sem) {
        if (!sem) return '';
        var label = semesterLabel(sem.term);
        var year = text(sem.schoolYear);
        if (year && label) return year + '学年' + label;
        return label || (year ? year + '学年' : '');
    }

    function cmpSemesterAsc(a, b) {
        if (a.schoolYear !== b.schoolYear) return cmpStr(a.schoolYear, b.schoolYear);
        var x = intOf(a.id);
        var y = intOf(b.id);
        if (x === null) x = 0;
        if (y === null) y = 0;
        return cmpNum(x, y);
    }

    function pickCurrentSemester() {
        if (!semesters.length) return { sem: null, reason: 'none' };
        var sorted = semesters.slice(0).sort(cmpSemesterAsc);
        var i;
        if (currentSemesterId) {
            for (i = 0; i < sorted.length; i++) {
                if (sorted[i].id === currentSemesterId) return { sem: sorted[i], reason: 'id' };
            }
        }
        var indexText = firstMatch(courseHtml, [RE_SEM_INDEX]) || firstMatch(entryHtml, [RE_SEM_INDEX]);
        var indexOrdinal = intOf(indexText);
        if (indexOrdinal !== null && indexOrdinal >= 0 && indexOrdinal < sorted.length) {
            return { sem: sorted[indexOrdinal], reason: 'index' };
        }
        // 当前日期落在学期起止日期之间 → 就是它
        for (i = 0; i < sorted.length; i++) {
            var sem = sorted[i];
            if (sem.startDate && sem.endDate && sem.startDate <= todayIso && todayIso <= sem.endDate) {
                return { sem: sem, reason: 'date' };
            }
        }
        // 否则取最近一个已经开始的学期
        var picked = sorted[0];
        for (i = 0; i < sorted.length; i++) {
            if (sorted[i].startDate && sorted[i].startDate <= todayIso) picked = sorted[i];
        }
        return { sem: picked, reason: 'latest' };
    }

    var current = pickCurrentSemester().sem;

    var termName = semesterNameOf(current);
    var termNameFromEdu = !!(current && current.schoolYear && current.term);
    if (!termName) {
        termName = SCHOOL_NAME + ' ' + academicTermName(todayIso);
        warn('学期名教务没有提供，已按导入日期推算为「' + termName + '」，如与实际不符可在学期管理里改名');
    } else if (!termNameFromEdu) {
        warn(
            '学期名只取到一半（教务的学期列表里学年或学期号缺一个），已拼成「' + termName +
            '」，如与实际不符可在学期管理里改名'
        );
    }

    // ---------- 开学日 ----------
    // 学期起止日期 → 学期总周数（含首尾，按自然周向上取整）
    function weeksBetween(startIso, endIso) {
        if (!startIso || !endIso || endIso < startIso) return null;
        var start = new Date(startIso + 'T00:00:00Z').getTime();
        var end = new Date(endIso + 'T00:00:00Z').getTime();
        var days = Math.round((end - start) / 86400000) + 1;
        var weeks = Math.ceil(days / 7);
        if (!(weeks >= 1)) return null;
        return weeks;
    }

    // 「学年 + 学期」→ 开学季的锚点日（还没回退到周一）。别把锚点直接当 firstDay（手册 §4.3）。
    function anchorFromTermInfo(schoolYear, termText) {
        var m = /([0-9]{4})/.exec(text(schoolYear));
        var year = m ? parseInt(m[1], 10) : null;
        var t = text(termText);
        var isFirst = t === '1' || t.indexOf('一') >= 0;
        var isSecond = t === '2' || t.indexOf('二') >= 0;
        if (year === null) return null;
        if (isFirst) return { year: year, month: 9, day: 1, rule: '学年里的第一学期按 9 月 1 日所在周的周一算' };
        if (isSecond) return { year: year + 1, month: 2, day: 20, rule: '学年里的第二学期按次年 2 月 20 日所在周的周一算' };
        return null;
    }

    // 什么线索都没有时的兜底：按今天的月份找最近的那个开学季。
    function anchorFromToday() {
        var today = new Date(todayIso + 'T00:00:00Z');
        var year = today.getUTCFullYear();
        var month = today.getUTCMonth() + 1;
        if (month >= 9) return { year: year, month: 9, day: 1, rule: '9 月以后按当年 9 月 1 日所在周的周一算' };
        if (month >= 2) return { year: year, month: 2, day: 20, rule: '2 月到 8 月按当年 2 月 20 日所在周的周一算' };
        return { year: year - 1, month: 9, day: 1, rule: '1 月按上一年 9 月 1 日所在周的周一算' };
    }

    var firstDay = null;
    var semesterWeeks = null;

    if (current && current.startDate) {
        firstDay = mondayOfYmd(
            parseInt(current.startDate.substring(0, 4), 10),
            parseInt(current.startDate.substring(5, 7), 10),
            parseInt(current.startDate.substring(8, 10), 10)
        );
        semesterWeeks = weeksBetween(current.startDate, current.endDate);
    }

    if (!firstDay) {
        var anchor = null;
        if (current) anchor = anchorFromTermInfo(current.schoolYear, current.term);
        if (!anchor) anchor = anchorFromToday();
        firstDay = mondayOfYmd(anchor.year, anchor.month, anchor.day);
        warn(
            '教务没有给出开学日期，第 1 周按「' + anchor.rule + '」推算为 ' + firstDay +
            '，请在学期管理里核对成学校实际开学日'
        );
    } else {
        warn('开学日期取自教务的学期起止日期：第 1 周从 ' + firstDay + ' 开始，请在学期管理里核对');
    }

    // ---------- 课表 HTML：unitCount ----------
    var RE_UNIT_COUNT = /\bvar\s+unitCount\s*=\s*([0-9]+)\s*;/;
    var unitCountMatch = RE_UNIT_COUNT.exec(courseHtml);
    var unitCount = unitCountMatch ? parseInt(unitCountMatch[1], 10) : DEFAULT_UNIT_COUNT;
    var unitCountGuessed = false;
    if (!(unitCount >= 1) || unitCount > 30) {
        unitCount = DEFAULT_UNIT_COUNT;
        unitCountGuessed = true;
    }
    if (!unitCountMatch) unitCountGuessed = true;

    // ---------- 课表 HTML：TaskActivity ----------
    // 上游的块正则：「activity = new TaskActivity(...)」之后、下一个活动开始之前的全部文本
    // 都算这门课的 index 赋值区（同一个活动可能有多个 index，例如连堂）。
    var RE_ACTIVITY_BLOCK = /activity\s*=\s*new\s+TaskActivity\(([\s\S]*?)\);([\s\S]*?)(?=var\s+actTeachers|var\s+teachers|activity\s*=\s*new|$)/g;
    var RE_INDEX_VAR = /index\s*=\s*([0-9]+)\s*\*\s*unitCount\s*\+\s*([0-9]+)/g;
    var RE_INDEX_LITERAL = /index\s*=\s*([0-9]+)\s*(?!\s*[*+\-])/g;
    var RE_TEACHERS_BEFORE = /var\s+actTeachers\s*=\s*\[([\s\S]*?)\]\s*;/g;
    var RE_TEACHER_NAME = /name\s*:\s*(?:"([^"]*)"|'([^']*)')/g;
    var RE_COURSE_NAME_BEFORE = /(?:var\s+)?courseName\s*=\s*(?:"([^"]*)"|'([^']*)')\s*;?/g;

    // 按逗号切参数，括号 / 方括号 / 花括号里的逗号不算分隔（join(",", …) 这类要留着）
    function splitJsArgs(argsText) {
        var args = [];
        var curr = '';
        var depth = 0;
        var quote = '';
        var i;
        var ch;
        for (i = 0; i < argsText.length; i++) {
            ch = argsText.charAt(i);
            if (quote) {
                curr += ch;
                if (ch === quote) quote = '';
                continue;
            }
            if (ch === '"' || ch === "'") { quote = ch; curr += ch; continue; }
            if (ch === '(' || ch === '[' || ch === '{') { depth++; curr += ch; continue; }
            if (ch === ')' || ch === ']' || ch === '}') { if (depth > 0) depth--; curr += ch; continue; }
            if (ch === ',' && depth === 0) { args.push(curr); curr = ''; continue; }
            curr += ch;
        }
        args.push(curr);
        return args;
    }

    function argAt(args, index) {
        if (index >= args.length) return '';
        return trimStr(args[index]);
    }

    function isQuoted(token) {
        var t = trimStr(token);
        if (t.length < 2) return false;
        var first = t.charAt(0);
        return (first === '"' || first === "'") && t.charAt(t.length - 1) === first;
    }

    // '张三' / "张三" → 张三；null / undefined → 空串；其它（变量、join(...) 表达式）原样返回
    function unquoteJsLiteral(token) {
        var t = trimStr(token);
        if (!t) return '';
        if (t === 'null' || t === 'undefined') return '';
        if (isQuoted(t)) return t.substring(1, t.length - 1);
        return t;
    }

    // 教师写成表达式时（「actTeachers.join(",")」 / 「teachers」这类），回看这门课的活动块之前
    // 最近一个「var actTeachers = [...]」，把里面的 name 取出来（上游同款做法）。
    function teachersBefore(fullText, blockIndex) {
        var start = Math.max(0, blockIndex - 2200);
        var segment = fullText.substring(start, blockIndex);
        RE_TEACHERS_BEFORE.lastIndex = 0;
        var m;
        var last = null;
        while ((m = RE_TEACHERS_BEFORE.exec(segment)) !== null) last = m[1];
        if (!last) return '';
        var names = [];
        RE_TEACHER_NAME.lastIndex = 0;
        var nm;
        while ((nm = RE_TEACHER_NAME.exec(last)) !== null) {
            var name = text(nm[1] || nm[2] || '');
            if (!name) continue;
            var seen = false;
            for (var i = 0; i < names.length; i++) if (names[i] === name) seen = true;
            if (!seen) names.push(name);
        }
        if (!names.length) return '';
        return names.join(',');
    }

    function courseNameBefore(fullText, blockIndex) {
        var start = Math.max(0, blockIndex - 3000);
        var segment = fullText.substring(start, blockIndex);
        RE_COURSE_NAME_BEFORE.lastIndex = 0;
        var m;
        var last = null;
        while ((m = RE_COURSE_NAME_BEFORE.exec(segment)) !== null) {
            var value = text(m[1] || m[2] || '');
            if (value) last = value;
        }
        return last;
    }

    // 教室写成表达式时（同族的其它适配器就是这么干的），回看这门课之前最近一个
    // 「var room = "..."」/「var position = "..."」/「var place = "..."」这类赋值。
    var RE_LOCATION_BEFORE = /(?:var\s+)?(?:room|roomName|position|place|classroom)\s*=\s*(?:"([^"]*)"|'([^']*)')\s*;?/gi;

    function locationBefore(fullText, blockIndex) {
        var start = Math.max(0, blockIndex - 3000);
        var segment = fullText.substring(start, blockIndex);
        RE_LOCATION_BEFORE.lastIndex = 0;
        var m;
        var last = null;
        while ((m = RE_LOCATION_BEFORE.exec(segment)) !== null) {
            var value = text(m[1] || m[2] || '');
            if (value) last = value;
        }
        return last;
    }

    // 课程名后面的课程序号（上游同款）："高等数学A(一)  (1)" 只去行尾的纯数字括号
    function cleanCourseName(name) {
        return text(name).replace(/\([0-9.]+\)\s*$/, '');
    }

    // 周次位图（本批统一口径）：下标 i 就是第 i 周，下标 0 是占位符。
    // 位图第 0 位写着 1 时不产出「第 0 周」，只记一笔（载荷校验也不收第 0 周）。
    function weeksFromBitmap(bitmap) {
        var s = text(bitmap);
        var weeks = [];
        if (!s) return { weeks: weeks, zeroBit: false, badChars: 0 };
        var i;
        for (i = 1; i < s.length; i++) {
            if (s.charAt(i) === '1') weeks.push(i);
        }
        var badChars = 0;
        for (i = 0; i < s.length; i++) {
            var ch = s.charAt(i);
            if (ch !== '0' && ch !== '1') badChars++;
        }
        return { weeks: weeks, zeroBit: s.charAt(0) === '1', badChars: badChars };
    }

    // 周次集合 → 极大段（手册 §4.1）：连续段 ALL，隔周段按首周奇偶给 ODD / EVEN
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

    var entries = [];
    var zeroBitBitmaps = 0;
    var badBitmapChars = 0;
    var indexFromLiteral = 0;
    var nameFromVariable = 0;
    var teacherFromVariable = 0;
    var locationFromVariable = 0;
    var badActivities = 0;

    RE_ACTIVITY_BLOCK.lastIndex = 0;
    var blockMatch;
    while ((blockMatch = RE_ACTIVITY_BLOCK.exec(courseHtml)) !== null) {
        var argsText = blockMatch[1];
        var scopeText = blockMatch[2];
        var args = splitJsArgs(argsText);
        if (args.length < 7) { badActivities++; continue; }

        var teacherRaw = argAt(args, 1);
        var teacher = unquoteJsLiteral(teacherRaw);
        if (teacherRaw && !isQuoted(teacherRaw)) {
            // 不是字面量：可能是 join(...) 表达式，也可能是个变量名。两种都回看 actTeachers；
            // 回看不到就把变量名丢掉（留空），绝不把「actTeachers」当成教师姓名显示。
            var resolvedTeacher = teachersBefore(courseHtml, blockMatch.index);
            if (resolvedTeacher) {
                teacher = resolvedTeacher;
                teacherFromVariable++;
            } else if (/^[A-Za-z_$][A-Za-z0-9_$.]*$/.test(teacher)) {
                teacher = '';
            }
        }
        teacher = text(teacher);

        var nameRaw = argAt(args, 3);
        var name = unquoteJsLiteral(nameRaw);
        if (nameRaw && !isQuoted(nameRaw) && nameRaw.indexOf('courseName') >= 0) {
            var resolvedName = courseNameBefore(courseHtml, blockMatch.index);
            if (resolvedName) {
                name = resolvedName;
                nameFromVariable++;
            }
        }
        name = cleanCourseName(name);

        // 教室：上游把 args[5] 原样当教室名（还会把尾巴上的括号注释整段去掉）。同族的其它适配器
        // 明确把教室写成 join(...) 表达式，所以这里同教师一样处理 —— 不是字面量就回看脚本变量，
        // 回看不到就留空，绝不把 roomVariable 这种标识符当教室显示。
        var locationRaw = argAt(args, 5);
        var location = unquoteJsLiteral(locationRaw).replace(/"/g, '').replace(/\([\s\S]*\)/g, '');
        if (locationRaw && !isQuoted(locationRaw)) {
            var resolvedLocation = locationBefore(courseHtml, blockMatch.index);
            if (resolvedLocation) {
                location = resolvedLocation;
                locationFromVariable++;
            } else if (/^[A-Za-z_$][A-Za-z0-9_$.]*$/.test(trimStr(location))) {
                location = '';
            }
        }
        location = text(location);
        var bitmap = unquoteJsLiteral(argAt(args, 6));
        var bitmapResult = weeksFromBitmap(bitmap);
        if (bitmapResult.zeroBit) zeroBitBitmaps++;
        badBitmapChars += bitmapResult.badChars;
        var weeks = bitmapResult.weeks;

        var day = -1;
        var sections = [];
        RE_INDEX_VAR.lastIndex = 0;
        var indexMatch;
        while ((indexMatch = RE_INDEX_VAR.exec(scopeText)) !== null) {
            var rawDay = parseInt(indexMatch[1], 10);
            var rawSection = parseInt(indexMatch[2], 10);
            if (rawDay < 0 || rawDay > 6) continue;
            if (rawSection < 0 || rawSection >= unitCount) continue;
            day = rawDay + 1;
            sections.push(rawSection + 1);
        }
        if (!sections.length) {
            // 同族有人把 index 预先算好再赋值（「index = 62」）。用 unitCount 反解：
            // index = 星期 * unitCount + 节次，这里的星期 / 节次都是 0 基。
            var literal = /index\s*=\s*([0-9]+)\s*;/.exec(scopeText);
            if (literal) {
                var value = parseInt(literal[1], 10);
                var literalDay = Math.floor(value / unitCount);
                var literalSection = value - literalDay * unitCount;
                if (literalDay >= 0 && literalDay <= 6 && literalSection >= 0 && literalSection < unitCount) {
                    day = literalDay + 1;
                    sections.push(literalSection + 1);
                    indexFromLiteral++;
                }
            }
        }

        if (day < 1 || !sections.length) { badActivities++; continue; }
        if (!weeks.length) { badActivities++; continue; }

        sections.sort(cmpNum);

        var dropped = 0;
        for (var w = 0; w < weeks.length; w++) {
            if (weeks[w] > MAX_TOTAL_WEEKS) { dropped++; }
        }
        if (dropped) {
            var kept = [];
            for (w = 0; w < weeks.length; w++) if (weeks[w] <= MAX_TOTAL_WEEKS) kept.push(weeks[w]);
            weeks = kept;
        }

        entries.push({
            name: name,
            teacher: teacher,
            location: location || null,
            day: day,
            startSection: sections[0],
            endSection: sections[sections.length - 1],
            weeks: weeks,
            weeksKey: weeks.join(','),
            droppedWeeks: dropped
        });
    }

    if (!entries.length) {
        throw new Error(
            '没能从课表数据里解析出任何课程：可能这个学期还没排课，或教务系统改了课表的返回格式。' +
            '请先在教务页面里确认「我的课表」能看到课，再点「提取课表」'
        );
    }

    // ---------- 合并连堂 + 分组课程 ----------
    entries.sort(function (a, b) {
        return cmpStr(a.name, b.name) || cmpStr(a.teacher, b.teacher) ||
            cmpStr(a.location === null ? '' : a.location, b.location === null ? '' : b.location) ||
            cmpNum(a.day, b.day) || cmpStr(a.weeksKey, b.weeksKey) ||
            cmpNum(a.startSection, b.startSection) || cmpNum(a.endSection, b.endSection);
    });

    var merged = [];
    for (var e = 0; e < entries.length; e++) {
        var item = entries[e];
        var prev = merged.length ? merged[merged.length - 1] : null;
        var same = prev && prev.name === item.name && prev.teacher === item.teacher &&
            prev.location === item.location && prev.day === item.day && prev.weeksKey === item.weeksKey;
        if (same && prev.endSection + 1 === item.startSection) {
            prev.endSection = item.endSection;
        } else {
            merged.push({
                name: item.name,
                teacher: item.teacher,
                location: item.location,
                day: item.day,
                startSection: item.startSection,
                endSection: item.endSection,
                weeksKey: item.weeksKey,
                weeks: item.weeks
            });
        }
    }

    var maxPeriod = 1;
    var maxWeek = 0;
    var droppedWeeks = 0;
    for (e = 0; e < merged.length; e++) {
        if (merged[e].endSection > maxPeriod) maxPeriod = merged[e].endSection;
        for (var k = 0; k < merged[e].weeks.length; k++) {
            if (merged[e].weeks[k] > maxWeek) maxWeek = merged[e].weeks[k];
        }
    }
    for (e = 0; e < entries.length; e++) droppedWeeks += entries[e].droppedWeeks;

    // ---------- 总周数 ----------
    var weeksSource;
    var totalWeeks;
    if (semesterWeeks) {
        totalWeeks = semesterWeeks;
        weeksSource = '教务学期列表的起止日期（约 ' + semesterWeeks + ' 周）';
    } else {
        totalWeeks = FALLBACK_TOTAL_WEEKS;
        weeksSource = '适配器内置的 ' + FALLBACK_TOTAL_WEEKS + ' 周（教务没有给出学期总周数）';
    }
    var raised = false;
    if (maxWeek > totalWeeks) {
        totalWeeks = maxWeek;
        raised = true;
    }
    var clamped = false;
    if (totalWeeks > MAX_TOTAL_WEEKS) {
        totalWeeks = MAX_TOTAL_WEEKS;
        clamped = true;
    }

    // 按总周数裁一遍块（超出的周次丢掉，一块都不剩的课程不放进载荷）
    var courseOrder = [];
    var byCourse = {};
    for (e = 0; e < merged.length; e++) {
        var unit = merged[e];
        var weeksInRange = [];
        for (k = 0; k < unit.weeks.length; k++) {
            if (unit.weeks[k] <= totalWeeks) weeksInRange.push(unit.weeks[k]);
        }
        if (!weeksInRange.length) continue;
        var key = unit.name + SEP + unit.teacher;
        if (!byCourse[key]) {
            byCourse[key] = {
                name: unit.name,
                teacher: unit.teacher || null,
                note: null,
                blocks: [],
                missingLocation: unit.location === null
            };
            courseOrder.push(key);
        }
        if (unit.location === null) byCourse[key].missingLocation = true;
        var runs = runsOf(weeksInRange);
        for (var r = 0; r < runs.length; r++) {
            byCourse[key].blocks.push({
                dayOfWeek: unit.day,
                startPeriod: unit.startSection,
                endPeriod: unit.endSection,
                startWeek: runs[r].start,
                endWeek: runs[r].end,
                weekType: runs[r].weekType,
                location: unit.location
            });
        }
    }

    if (!courseOrder.length) {
        throw new Error('课表里的课程周次都超出了学期总周数，没能生成任何课程：请反馈这条课表');
    }

    var courses = [];
    var noTeacher = 0;
    var noLocation = 0;
    for (e = 0; e < courseOrder.length; e++) {
        var course = byCourse[courseOrder[e]];
        course.blocks.sort(function (a, b) {
            return cmpNum(a.dayOfWeek, b.dayOfWeek) || cmpNum(a.startPeriod, b.startPeriod) ||
                cmpNum(a.endPeriod, b.endPeriod) || cmpNum(a.startWeek, b.startWeek) ||
                cmpNum(a.endWeek, b.endWeek) || cmpStr(a.weekType, b.weekType) ||
                cmpStr(a.location === null ? '' : a.location, b.location === null ? '' : b.location);
        });
        if (!course.teacher) noTeacher++;
        if (course.missingLocation) noLocation++;
        courses.push({ name: course.name, teacher: course.teacher, note: null, blocks: course.blocks });
    }

    if (!courses.length) {
        throw new Error('课表里的课程周次都超出了学期总周数，没能生成任何课程：请反馈这条课表');
    }

    // ---------- 作息（各节） ----------
    // min(作息表长度, unitCount) 是「这个教学班一整天有几节课」，作息表里有的就都给出去，
    // 课程用到的更晚节次再往后补。
    var publishPeriods = Math.min(PERIOD_TIMES.length, unitCount);
    if (!(publishPeriods >= 1)) publishPeriods = PERIOD_TIMES.length;
    if (maxPeriod > publishPeriods) publishPeriods = maxPeriod;
    if (publishPeriods > PERIOD_TIMES.length) publishPeriods = PERIOD_TIMES.length;

    var periodTimes = [];
    var badSlots = 0;
    for (var p = 0; p < publishPeriods; p++) {
        var slot = PERIOD_TIMES[p];
        var slotStart = timeOf(slot.start);
        var slotEnd = timeOf(slot.end);
        if (!slotStart || !slotEnd || minutesOf(slotStart) >= minutesOf(slotEnd)) { badSlots++; continue; }
        periodTimes.push({ periodIndex: slot.periodIndex, start: slotStart, end: slotEnd });
    }
    var uncoveredFrom = 0;
    if (maxPeriod > PERIOD_TIMES.length) uncoveredFrom = PERIOD_TIMES.length + 1;

    // ---------- warnings ----------
    warn('只导入了教务系统当前选中的学期（' + termName + '）；要导入别的学期，请在教务页面里切到那个学期再点「提取课表」');

    warn(
        '学期总周数用的是' + weeksSource +
        (raised ? '，并按课表里最晚的第 ' + maxWeek + ' 周抬高' : '') +
        (clamped ? '，再按载荷上限 ' + MAX_TOTAL_WEEKS + ' 周截断' : '') +
        '，如与实际不符可在学期管理里改'
    );

    warn(
        '作息时间用的是适配器内置的' + SCHOOL_NAME + ' ' + PERIOD_TIMES.length + ' 节作息表（第 1 节 ' +
        PERIOD_TIMES[0].start + '-' + PERIOD_TIMES[0].end + '），不是从教务读的，真机上请对照教务处公布的作息核对'
    );

    if (unitCountGuessed) {
        warn(
            '课表页面里没读到节次数（unitCount），已按缺省的 ' + unitCount +
            ' 节解析：这门课的节次可能与实际差几节，请核对课表，如不对请反馈'
        );
    }

    if (zeroBitBitmaps > 0) {
        warn(
            '有 ' + zeroBitBitmaps + ' 条排课的周次位图第 0 位为 1，与「第 0 位是占位符」的约定不符，' +
            '已忽略这一位（不会产生第 0 周），请在导入预览里核对周次'
        );
    }
    if (droppedWeeks > 0) {
        warn('有 ' + droppedWeeks + ' 个周次超出 1-' + MAX_TOTAL_WEEKS + ' 周，已丢弃（教务给出的周次不正常）');
    }
    if (badBitmapChars > 0) {
        warn('周次位图里有 ' + badBitmapChars + ' 个不是 0/1 的字符，已按「不是 1 就不排课」处理，请核对周次');
    }
    if (badActivities > 0) {
        warn(
            '有 ' + badActivities + ' 条排课没能解析（参数不足 / 认不出星期与节次 / 没有周次），已跳过：' +
            '教务数据不完整时会出现，如发现少课请反馈'
        );
    }
    if (indexFromLiteral > 0) {
        warn(
            '有 ' + indexFromLiteral + ' 条排课的 index 是预先算好的数字（不是「星期*unitCount+节次」的写法），' +
            '已按当前节次数反解，请核对这些课的星期与节次'
        );
    }
    if (nameFromVariable > 0) {
        warn('有 ' + nameFromVariable + ' 条排课的课程名写在脚本变量里，已按变量取值还原，请核对课名');
    }
    if (teacherFromVariable > 0) {
        warn('有 ' + teacherFromVariable + ' 条排课的教师写在脚本变量里，已按变量取值还原，请核对教师');
    }
    if (locationFromVariable > 0) {
        warn('有 ' + locationFromVariable + ' 条排课的教室写在脚本变量里，已按变量取值还原，请核对教室');
    }
    warn(
        '本适配器不写入教师与教室的占位文案：解析不出来就留空（空着比写「未知教师」好，后者会被课表当成真姓名显示）'
    );
    if (noTeacher > 0) {
        warn('有 ' + noTeacher + ' 门课没能解析出教师，教师一栏按「留空」处理（写「未知教师」会被当成真姓名显示），请核对');
    }
    if (noLocation > 0) {
        warn('有 ' + noLocation + ' 门课没能解析出教室，教室一栏按「留空」处理，请核对');
    }
    if (badSlots > 0) {
        warn('适配器内置作息表里有 ' + badSlots + ' 节的时间不合法，已丢弃这些节次，请反馈');
    }
    if (uncoveredFrom > 0) {
        warn(
            '课表里用到第 ' + maxPeriod + ' 节，而内置作息表只到第 ' + PERIOD_TIMES.length +
            ' 节：第 ' + uncoveredFrom + ' 节及之后没有上下课时间，请核对'
        );
    }

    var warnings = allWarnings;
    if (allWarnings.length > MAX_WARNINGS) {
        warnings = allWarnings.slice(0, MAX_WARNINGS - 1);
        warnings.push(
            '另有 ' + (allWarnings.length - MAX_WARNINGS + 1) + ' 条说明因为超出上限没有显示，请把这份课表反馈给我们'
        );
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
