(function () {
    // 郑州航空工业管理学院教务适配器（树维 EAMS 平台）—— 第二步：教务原始数据 → 空课课表载荷。
    //
    // 平台是**上海树维信息科技有限公司（SupWisdom，新开普子公司）的综合教务系统**（路径带 /eams/），
    // 不是湖南强智 —— 本批（批次四）统一订正过这个厂商名，详见 AUDIT.md。
    //
    // 移植自 shiguang_warehouse 的 ZUA/zua.js
    //   https://github.com/XingHeYuZhuan/shiguang_warehouse  （MIT，上游 maintainer xBefore）
    //   上游快照 e62554a4034386b893bcd6813c7b2b64f8c730a3（2026-09-12）
    //
    // 上游在同一段脚本里请求接口、解析课表 HTML 里内嵌的 TaskActivity、算周次、存学期配置与作息；
    // 这里只做纯转换（不碰页面、不发请求，CI 用 Rhino 实跑），取数全部在 extract.js 里。
    //
    // 移植改动（逐条，细则与依据见 AUDIT.md）：
    //   ① ES6 → ES5：去掉异步写法、模板串、箭头函数、块级声明与对象展开（这一半是纯函数，
    //      本来就同步，Promise 用不上）
    //   ② 上游用 showSingleSelection 弹窗选学期、用 showPrompt 问开学日；本件一律不问用户
    //   ③ 周次位图按本批统一口径：下标 i 就是第 i 周，下标 0 是占位符（上游 zua 正是从 week=1 起，
    //      与统一口径一致）；位图第 0 位为 1 时不产出「第 0 周」，改为写一条 warnings。
    //      上游还把 0 位当占位符这件事写进了循环条件，本件显式计数并出声
    //   ④ 周次超过载荷上限 30 时 clamp 到 30 并 warn（上游的 MAX_SUPPORTED_WEEK 是 60，而我们的
    //      totalWeeks / startWeek / endWeek 校验都是 1..30，越界会让整个载荷被拒）
    //   ⑤ unitCount 从课表 HTML 读（上游缺省 14）；读不到时**同时**写 warnings，不静默用一个数字
    //   ⑥ 作息时间优先用课表表头读到的 (HH:mm-HH:mm)，读不到才回落到上游那张内置表；两种情况都
    //      写 warnings。所有时间过 HH:mm 与 00:00-23:59 校验（越界会让整个载荷被拒，不是跳过一节）
    //   ⑦ 开学日：优先用学期日历（calendar-info）的学期起止日期，按 firstDayOfWeek 回退对齐
    //      （缺省周一，手册 §4.3）；拿不到就按最近的每周起始日推算，并如实写进 warnings。
    //      上游是 showPrompt 问用户 + 写死 firstDayOfWeek = 1，问用户的路径没有移植
    //   ⑧ 教师 / 教室拿不到就留空（null），不用上游的「未知教师」「未知地点」占位
    //      （手册 §4.7：占位符会被课表当成真姓名、真地点显示）
    //   ⑨ 学期名用教务给的；拿不到用「郑州航空工业管理学院 + 学年学期」，不用适配器名
    //   ⑩ 课程名按原文（上游 cleanCourseName 会把名字末尾的半角括号内容删掉，
    //      「大学英语(一)」→「大学英语」；本件保留原文，理由见 AUDIT.md）
    //   ⑪ 上游的 mergeContinuousLessons 原样移植：按（课名|教师|地点|星期）分组、逐周记下这节课
    //      占用的节次集合，再把每周的节次切成连续段 —— 这是树维 EAMS 的语义（同一门课不同周可以
    //      落在不同节次上），但排序键换成与引擎无关的确定性比较（上游用 localeCompare，
    //      Rhino 与 V8 的结果未必一致，而 fixture 是逐数组比对的）
    //   ⑫ 上游静默丢数据的三处都改成出声：位图里一位都没有的活动、TaskActivity 参数不足的活动、
    //      星期或节次超范围的下标，全部计数后写进 warnings（手册 §4：不许静默丢课）
    //   ⑬ 新增 warnings：只导入当前学期、学期名/开学日/总周数/作息/unitCount 各自的来源、
    //      位图第 0 位、周次 clamp、TaskActivity 数量对账
    //   ⑭ 上游的 showToast / saveImportedCourses / saveCourseConfig / savePresetTimeSlots /
    //      notifyTaskCompletion 这些桥调用全部没有移植：我们这条链路是「拉」不是「推」，
    //      作息表也不单独推，直接放进载荷的 periodTimes
    //
    // 输入（extract.js 交出来的原始数据，契约见 AUDIT.md §「取数契约」）：
    //   { today, semester, semesters, currentSemesterId, firstDayOfWeek,
    //     calendarHtml, tableHtml, courseHtml, taskActivityCount, extractWarnings }

    var data = JSON.parse(typeof __ncInput === 'undefined' ? '{}' : __ncInput);

    var MAX_WEEK = 30;              // 载荷校验：totalWeeks ∈ 1..30，startWeek/endWeek ∈ 1..totalWeeks
    var MAX_WARNINGS = 20;          // 载荷校验：warnings ≤ 20 条
    var MAX_WARNING_CHARS = 200;    // 载荷校验：每条 ≤ 200 字
    var DEFAULT_UNIT_COUNT = 14;    // 上游 zua.js 的 unitCount 缺省值
    var DEFAULT_TOTAL_WEEKS = 20;   // 上游 zua.js 没有缺省总周数，用同族（qdhhc / masu）的 20
    var SCHOOL_NAME = '郑州航空工业管理学院';

    // 学校作息（郑州航空工业管理学院，10 节）：上游 ZUA/zua.js 里那张 ZUA_TIME_SLOTS 原样搬过来
    // （上游字段名是 number / startTime / endTime，这里换成载荷的 periodIndex / start / end）。
    // 上游没有向教务请求作息，这张表是脚本作者对学校的了解 —— 所以页面上取不到时用它必须出声。
    var BUILTIN_PERIOD_TIMES = [
        { periodIndex: 1, start: '08:00', end: '08:45' },
        { periodIndex: 2, start: '08:55', end: '09:40' },
        { periodIndex: 3, start: '10:00', end: '10:45' },
        { periodIndex: 4, start: '10:55', end: '11:40' },
        { periodIndex: 5, start: '14:30', end: '15:15' },
        { periodIndex: 6, start: '15:25', end: '16:10' },
        { periodIndex: 7, start: '16:30', end: '17:15' },
        { periodIndex: 8, start: '17:25', end: '18:10' },
        { periodIndex: 9, start: '19:30', end: '20:15' },
        { periodIndex: 10, start: '20:25', end: '21:10' }
    ];

    // 空课内置节次表（:core:model 的 DefaultPeriodTimes，12 节）：课表用到的节次超出作息表时
    // 用它把缺的节次补出来（载荷带了 periodTimes 时应用不会自己补），补了要写进 warnings。
    var HOST_PERIOD_TIMES = [
        { periodIndex: 11, start: '20:20', end: '21:05' },
        { periodIndex: 12, start: '21:15', end: '22:00' }
    ];

    var NBSP = String.fromCharCode(160);
    var SEP = String.fromCharCode(0);   // 复合键分隔符：用 fromCharCode 取，源码里不出现控制字符

    // ---------- 小工具 ----------
    function tidy(value) {
        if (value === null || value === undefined) return '';
        return String(value).replace(/\s+/g, ' ').trim();
    }

    function intOf(value) {
        var n = parseInt(value, 10);
        return isNaN(n) ? null : n;
    }

    function pad2(n) {
        return (n < 10 ? '0' : '') + n;
    }

    function isoOf(date) {
        return date.getUTCFullYear() + '-' + pad2(date.getUTCMonth() + 1) + '-' + pad2(date.getUTCDate());
    }

    // 数字年月日 → ISO 日期。用 UTC 构造再逐项回比，2 月 30 日这类假日期会被拒（同上游 normalizeNumericDate）
    function isoFromNumbers(year, month, day) {
        var y = intOf(year);
        var m = intOf(month);
        var d = intOf(day);
        if (y === null || m === null || d === null) return null;
        var date = new Date(Date.UTC(y, m - 1, d));
        if (date.getUTCFullYear() !== y || date.getUTCMonth() + 1 !== m || date.getUTCDate() !== d) return null;
        return isoOf(date);
    }

    // 任意文本里的第一个日期（"2026-09-07" / "2026/9/7" / "2026年9月7日" 都认）
    function isoFromText(value) {
        var m = /(\d{4})\s*[-/.年]\s*(\d{1,2})\s*[-/.月]\s*(\d{1,2})/.exec(tidy(value));
        if (!m) return null;
        return isoFromNumbers(m[1], m[2], m[3]);
    }

    function isoShift(iso, days) {
        var y = intOf(iso.substring(0, 4));
        var m = intOf(iso.substring(5, 7));
        var d = intOf(iso.substring(8, 10));
        var date = new Date(Date.UTC(y, m - 1, d));
        return isoOf(new Date(date.getTime() + days * 86400000));
    }

    // ISO 日期是星期几：1 = 周一 … 7 = 周日（与上游 firstDayOfWeek、手册 §4.3 同一套编号）
    function weekdayOfIso(iso) {
        var y = intOf(iso.substring(0, 4));
        var m = intOf(iso.substring(5, 7));
        var d = intOf(iso.substring(8, 10));
        var wd = new Date(Date.UTC(y, m - 1, d)).getUTCDay();
        return wd === 0 ? 7 : wd;
    }

    function weekdayCn(value) {
        return ['', '周一', '周二', '周三', '周四', '周五', '周六', '周日'][value] || ('星期' + value);
    }

    // 手册 §4.3：开学日要回退到「星期几 = firstDayOfWeek」的那一天（含当天）
    function isoOnOrBefore(iso, firstDayOfWeek) {
        var offset = (weekdayOfIso(iso) - firstDayOfWeek + 7) % 7;
        return isoShift(iso, -offset);
    }

    // "08:00" / "8:00" / "08:00:00" → "08:00"；认不出来（含 24:00 这种越界）返回 null。
    // 写进载荷 periodTimes 的时间必须是 00:00-23:59 的 HH:mm，越界会让整个载荷被拒（不是跳过一节）
    function timeOf(value) {
        var m = /^([01]?\d|2[0-3]):([0-5]\d)/.exec(tidy(value));
        if (!m) return null;
        return (m[1].length < 2 ? '0' + m[1] : m[1]) + ':' + m[2];
    }

    function minutesOf(hhmm) {
        return intOf(hhmm.substring(0, 2)) * 60 + intOf(hhmm.substring(3, 5));
    }

    function localTodayIso() {
        var now = new Date();
        return now.getFullYear() + '-' + pad2(now.getMonth() + 1) + '-' + pad2(now.getDate());
    }

    // ---------- warnings ----------
    var allWarnings = [];

    function warn(message) {
        var line = String(message);
        if (line.length > MAX_WARNING_CHARS) line = line.substring(0, MAX_WARNING_CHARS - 1) + '…';
        allWarnings.push(line);
    }

    var extractWarnings = data.extractWarnings;
    if (Object.prototype.toString.call(extractWarnings) === '[object Array]') {
        for (var ew = 0; ew < extractWarnings.length; ew++) {
            var line = tidy(extractWarnings[ew]);
            if (line) warn(line);
        }
    }

    // ---------- 学期信息 ----------
    var semester = data.semester && typeof data.semester === 'object' ? data.semester : {};
    var semesters = Object.prototype.toString.call(data.semesters) === '[object Array]' ? data.semesters : [];

    // 当前学期：优先用 extract 挑出来的那一条，其次按 id 在列表里找
    var currentId = tidy(data.currentSemesterId) || tidy(semester.id);
    var picked = null;
    if (currentId) {
        for (var si = 0; si < semesters.length; si++) {
            if (tidy(semesters[si] && semesters[si].id) === currentId) { picked = semesters[si]; break; }
        }
    }
    if (!picked && semesters.length === 1) picked = semesters[0];
    if (!picked) picked = semester;

    // 学期名：教务给了就用（手册 §4.7）。但树维的 dataQuery 有时把学期名叫「第一学期」/「第二学期」，
    // 单看它看不出是哪一学年 —— 这种名字前面补上学年，免得学期管理里出现一个光秃秃的「第一学期」。
    // 只有「学期名 + 学年」都拿不到时才拼「学校名 + 学年学期」，绝不用适配器名（手册 §4.7）。
    function yearLabel() {
        var year = tidy(semester.schoolYear) || tidy(picked && picked.schoolYear);
        return /^\d{4}-\d{2,4}$/.test(year) ? year : '';
    }

    function usableTermName(value, year) {
        var name = tidy(value).replace(/^["']|["']$/g, '');
        if (!name) return '';
        if (/^[0-9]+$/.test(name)) return '';
        if (year && /^第?\s*[0-9一二三四五六七八九]{1,2}\s*学期$/.test(name)) {
            if (!/学年/.test(name)) name = year + '学年' + name;
        }
        if (name.length > 60) name = name.substring(0, 60);
        return name;
    }

    // 学期序号：树维的 name 可能是「第一学期」也可能只是「1」
    function termNumber(value) {
        var text = tidy(value);
        if (/^[0-9]+$/.test(text)) return intOf(text);
        var cn = /第?\s*([一二三四五六七八九])\s*学期/.exec(text);
        if (!cn) return null;
        var digits = { '一': 1, '二': 2, '三': 3, '四': 4, '五': 5, '六': 6, '七': 7, '八': 8, '九': 9 };
        return digits[cn[1]] || null;
    }

    function fallbackTermName() {
        var year = yearLabel();
        if (!year) {
            // 学年也拿不到：按导入日期猜（1-8 月算第二学期，9-12 月算第一学期）
            var today = isoFromText(data.today) || localTodayIso();
            var startYear = intOf(today.substring(0, 4)) - (intOf(today.substring(5, 7)) <= 8 ? 1 : 0);
            year = startYear + '-' + (startYear + 1);
        }
        var number = termNumber(semester.term) || termNumber(picked && picked.term) ||
            termNumber(semester.name) || termNumber(picked && picked.name);
        var termCn = number ? ['', '一', '二', '三'][number] : '';
        if (!termCn) return SCHOOL_NAME + ' ' + year + '学年';
        return SCHOOL_NAME + ' ' + year + '学年' + (number === 1 ? '第一学期' : '第' + termCn + '学期');
    }

    var termName = usableTermName(semester.name, yearLabel()) || usableTermName(picked && picked.name, yearLabel());
    var termNameFromSchool = false;
    if (!termName) {
        termName = fallbackTermName();
        termNameFromSchool = true;
    }

    // ---------- 周次位图 ----------
    // 本批统一口径：下标 i 就是第 i 周，下标 0 是占位符。上游 zua.js 的循环是
    //   for (week = 1; week < value.length && week <= MAX_SUPPORTED_WEEK; week++)
    // 与本口径一致，所以照抄；但 0 位为 1 时上游一个字都不说，本件出声。
    var zeroBitCourses = 0;     // 位图第 0 位为 1 的活动数
    var clampedWeeks = 0;       // 超过第 30 周被 clamp 的周次数
    var clampedFrom = 0;        // 被 clamp 的周次里最大的那一个（写进 warnings 用）

    // 一位都没读到时返回空数组（调用方计数并出声）。位图短于最长周次时后面几位当 0，不算错误 ——
    // 短位图里的周次只会漏掉，不会多出来。字面量 "null" / "undefined"（引号包着的空值）当空位图
    function weeksOfBitmap(bitmap) {
        var value = bitmap === null || bitmap === undefined ? '' : String(bitmap);
        if (value === 'null' || value === 'undefined') value = '';
        if (value.length > 0 && value.charAt(0) === '1') zeroBitCourses++;
        var weeks = [];
        var seen = {};
        for (var week = 1; week < value.length; week++) {
            if (value.charAt(week) !== '1') continue;
            var target = week;
            if (week > MAX_WEEK) {
                clampedWeeks++;
                if (week > clampedFrom) clampedFrom = week;
                target = MAX_WEEK;
            }
            if (!seen[target]) { seen[target] = true; weeks.push(target); }
        }
        weeks.sort(function (a, b) { return a - b; });
        return weeks;
    }

    // 周次集合 → 极大段：步长 1 视作每周，步长 2 视作单周 / 双周（手册 §4.1）
    function runsOf(weeks) {
        var runs = [];
        var i = 0;
        while (i < weeks.length) {
            var step = 1;
            if (i + 1 < weeks.length && weeks[i + 1] - weeks[i] === 2) step = 2;
            var j = i;
            while (j + 1 < weeks.length && weeks[j + 1] - weeks[j] === step) j++;
            var run = { start: weeks[i], end: weeks[j], weekType: 'ALL' };
            if (step === 2 && run.start !== run.end) {
                run.weekType = run.start % 2 === 1 ? 'ODD' : 'EVEN';
            }
            runs.push(run);
            i = j + 1;
        }
        return runs;
    }

    // ---------- 课表 HTML 里的 TaskActivity ----------
    function matchParen(source, open) {
        var depth = 0;
        var quote = '';
        for (var i = open; i < source.length; i++) {
            var ch = source.charAt(i);
            if (quote) {
                if (ch === '\\') { i++; continue; }
                if (ch === quote) quote = '';
                continue;
            }
            if (ch === '"' || ch === "'") { quote = ch; continue; }
            if (ch === '(') depth++;
            else if (ch === ')') {
                depth--;
                if (depth === 0) return i;
            }
        }
        return -1;
    }

    // 按顶层逗号切参数（引号内的逗号、括号里的逗号都不算）—— 上游 powerSplit 的同款做法，
    // 用状态机重写（不用正则，也不用 eval / new Function）
    function splitArgs(source) {
        var args = [];
        var current = '';
        var depth = 0;
        var quote = '';
        for (var i = 0; i < source.length; i++) {
            var ch = source.charAt(i);
            if (quote) {
                current = current + ch;
                if (ch === '\\') {
                    if (i + 1 < source.length) { current = current + source.charAt(i + 1); i++; }
                    continue;
                }
                if (ch === quote) quote = '';
                continue;
            }
            if (ch === '"' || ch === "'") { quote = ch; current = current + ch; continue; }
            if (ch === '(' || ch === '[' || ch === '{') depth++;
            if (ch === ')' || ch === ']' || ch === '}') depth--;
            if (ch === ',' && depth === 0) { args.push(cleanArg(current)); current = ''; continue; }
            current = current + ch;
        }
        args.push(cleanArg(current));
        return args;
    }

    // 上游 cleanArg：trim；字面 null 当空值；剥掉最外层的引号
    function cleanArg(value) {
        var trimmed = String(value === null || value === undefined ? '' : value).replace(/^\s+|\s+$/g, '');
        if (trimmed === 'null' || trimmed === 'undefined') return null;
        return trimmed.replace(/^["']|["']$/g, '');
    }

    // 教师名块：形如 var teachers = [...] 与 var actTeachers = [...]。两种写法都要认（正则里写
    // 词首边界那种形式会漏掉 actTeachers：它前面的 act 与 T 之间没有词边界）
    var TEACHER_DECL_RE = /(?:^|[^A-Za-z0-9_$])(?:act)?teachers\s*=\s*\[/gi;
    var TEACHER_NAME_RE = /["']?name["']?\s*:\s*["']([^"']*)["']/g;

    function teacherBlocksOf(source) {
        var blocks = [];
        var re = new RegExp(TEACHER_DECL_RE.source, 'g');
        var m;
        while ((m = re.exec(source)) !== null) {
            var open = source.indexOf('[', m.index + m[0].length - 1);
            var close = open < 0 ? -1 : matchBracket(source, open);
            var names = [];
            if (close > open) {
                var body = source.substring(open + 1, close);
                var nameRe = new RegExp(TEACHER_NAME_RE.source, 'g');
                var nm;
                while ((nm = nameRe.exec(body)) !== null) {
                    var name = tidy(nm[1]);
                    if (name && names.indexOf(name) < 0) names.push(name);
                }
            }
            blocks.push({ start: m.index, names: names.join(',') });
            re.lastIndex = m.index + m[0].length;
        }
        return blocks;
    }

    function matchBracket(source, open) {
        var depth = 0;
        var quote = '';
        for (var i = open; i < source.length; i++) {
            var ch = source.charAt(i);
            if (quote) {
                if (ch === '\\') { i++; continue; }
                if (ch === quote) quote = '';
                continue;
            }
            if (ch === '"' || ch === "'") { quote = ch; continue; }
            if (ch === '[') depth++;
            else if (ch === ']') {
                depth--;
                if (depth === 0) return i;
            }
        }
        return -1;
    }

    // 离某个位置最近的前面那个教师块（上游按 var teachers = 切块，等价于这个意思，
    // 但这样写对「块尾没有下一个教师声明」的情况更稳）
    function teacherBefore(blocks, position) {
        var found = '';
        for (var i = 0; i < blocks.length; i++) {
            if (blocks[i].start < position) found = blocks[i].names;
            else break;
        }
        return found;
    }

    function activitiesOf(source) {
        var list = [];
        var re = /new\s+TaskActivity\s*\(/g;
        var m;
        while ((m = re.exec(source)) !== null) {
            var open = m.index + m[0].length - 1;
            var close = matchParen(source, open);
            if (close < 0) break;
            list.push({
                argsText: source.substring(open + 1, close),
                start: m.index,
                end: close + 1
            });
            re.lastIndex = close + 1;
        }
        return list;
    }

    // index 赋值的两种写法（上游正则同时认 unitCount 变量与**已算好的数字**两路；
    // 本件把「整个线性下标都算好了」的 index = 62 也认下来 —— 上游漏了那种写法，
    // 会静默丢掉那节课。禁止 eval / new Function：表达式来自网络取回的 HTML，手册 §5 第 6 条）
    function indexRegexOf(unitCount) {
        return new RegExp(
            'index\\s*=\\s*(?:(\\d+)\\s*\\*\\s*(?:unitCount|' + unitCount + ')\\s*\\+\\s*(\\d+)|(\\d+))\\s*;',
            'g'
        );
    }

    var courseHtml = typeof data.courseHtml === 'string' ? data.courseHtml : '';
    var unitCountMatch = /\bunitCount\s*=\s*(\d+)\s*;/.exec(courseHtml);
    var unitCount = unitCountMatch ? intOf(unitCountMatch[1]) : null;
    var unitCountFromDefault = false;
    if (!(unitCount >= 1 && unitCount <= MAX_WEEK)) {
        unitCount = DEFAULT_UNIT_COUNT;
        unitCountFromDefault = true;
    }

    var teacherBlocks = teacherBlocksOf(courseHtml);
    var activities = activitiesOf(courseHtml);

    // 分组键与上游一致：课名|教师|地点|星期。逐周记下这门课占用的节次集合，再切连续段
    var groups = {};
    var groupOrder = [];
    var badArgs = 0;        // TaskActivity 参数不足 7 个的活动数
    var badIndex = 0;       // 星期 / 节次超范围的下标处数
    var noIndex = 0;        // 读了参数但一条 index 都没读到的活动数
    var noWeeks = 0;        // 周次位图里一位都没有的活动数

    for (var a = 0; a < activities.length; a++) {
        var activity = activities[a];
        var args = splitArgs(activity.argsText);
        if (args.length < 7) { badArgs++; continue; }

        var name = tidy(args[3]);
        var room = tidy(args[5]);
        if (!name) { badArgs++; continue; }

        var owner = teacherBefore(teacherBlocks, activity.start);
        var teacherExpr = args[1] === null ? '' : String(args[1]);
        var teacher = owner;
        if (!/join\s*\(|actTeacherName|teachers\s*\[/.test(teacherExpr)) {
            var literal = tidy(teacherExpr);
            if (literal) teacher = literal;
        }

        var weeks = weeksOfBitmap(args[6]);
        if (!weeks.length) { noWeeks++; continue; }

        var next = a + 1 < activities.length ? activities[a + 1].start : courseHtml.length;
        var scope = courseHtml.substring(activity.end, next);
        var indexRe = indexRegexOf(unitCount);
        var indexMatch;
        var matched = 0;
        while ((indexMatch = indexRe.exec(scope)) !== null) {
            var day;
            var section;
            if (indexMatch[1] !== undefined && indexMatch[1] !== null) {
                day = intOf(indexMatch[1]) + 1;
                section = intOf(indexMatch[2]) + 1;
            } else {
                var linear = intOf(indexMatch[3]);
                day = Math.floor(linear / unitCount) + 1;
                section = (linear % unitCount) + 1;
            }
            if (day < 1 || day > 7 || section < 1 || section > unitCount) { badIndex++; continue; }
            matched++;
            var key = name + SEP + teacher + SEP + room + SEP + day;
            if (!groups[key]) {
                groups[key] = { name: name, teacher: teacher, room: room, day: day, weekSections: {} };
                groupOrder.push(key);
            }
            var matrix = groups[key].weekSections;
            for (var w = 0; w < weeks.length; w++) {
                var week = weeks[w];
                if (!matrix[week]) matrix[week] = {};
                matrix[week][section] = true;
            }
        }
        if (!matched) noIndex++;
    }

    // ---------- 上游 mergeContinuousLessons 的语义：逐周把节次切成连续段 ----------
    var merged = [];
    for (var gi = 0; gi < groupOrder.length; gi++) {
        var group = groups[groupOrder[gi]];
        var sectionWeeks = {};      // "start-end" → 周次列表
        var weekKeys = [];
        for (var weekKey in group.weekSections) {
            if (group.weekSections.hasOwnProperty(weekKey)) weekKeys.push(parseInt(weekKey, 10));
        }
        weekKeys.sort(function (x, y) { return x - y; });
        for (var wk = 0; wk < weekKeys.length; wk++) {
            var week = weekKeys[wk];
            var used = [];
            for (var sectionKey in group.weekSections[week]) {
                if (group.weekSections[week].hasOwnProperty(sectionKey)) used.push(parseInt(sectionKey, 10));
            }
            used.sort(function (x, y) { return x - y; });
            var start = -1;
            var previous = -1;
            for (var u = 0; u < used.length; u++) {
                var section = used[u];
                if (start < 0) { start = section; previous = section; continue; }
                if (section === previous + 1) { previous = section; continue; }
                pushSectionRun(sectionWeeks, start, previous, week);
                start = section;
                previous = section;
            }
            if (start >= 0) pushSectionRun(sectionWeeks, start, previous, week);
        }
        for (var span in sectionWeeks) {
            if (!sectionWeeks.hasOwnProperty(span)) continue;
            var parts = span.split('-');
            var spans = sectionWeeks[span].slice(0);
            spans.sort(function (x, y) { return x - y; });
            merged.push({
                name: group.name,
                teacher: group.teacher,
                room: group.room,
                day: group.day,
                startSection: parseInt(parts[0], 10),
                endSection: parseInt(parts[1], 10),
                weeks: spans
            });
        }
    }

    function pushSectionRun(bucket, start, end, week) {
        var key = start + '-' + end;
        if (!bucket[key]) bucket[key] = [];
        bucket[key].push(week);
    }

    // 确定性排序（不用 localeCompare：它排中文的结果跟引擎有关，而 fixture 逐数组比对）
    function cmpStr(x, y) {
        if (x === y) return 0;
        return x < y ? -1 : 1;
    }

    function cmpNum(x, y) {
        return x === y ? 0 : (x < y ? -1 : 1);
    }

    merged.sort(function (x, y) {
        return cmpNum(x.day, y.day) || cmpNum(x.startSection, y.startSection) ||
            cmpNum(x.endSection, y.endSection) || cmpStr(x.name, y.name) ||
            cmpStr(x.teacher, y.teacher) || cmpStr(x.room, y.room) ||
            cmpStr(x.weeks.join(','), y.weeks.join(','));
    });

    // 课程顺序：按排序后第一次出现的排列（不依赖任何不稳定排序）。
    // 去重用的 seen 单独放一个对象 —— 不能挂在课程对象上，否则它会跟着载荷一起序列化出去
    var courses = [];
    var byCourse = {};
    var courseOrder = [];
    var courseSeen = {};
    var maxWeek = 0;
    for (var mi = 0; mi < merged.length; mi++) {
        var item = merged[mi];
        var courseKey = item.name + SEP + item.teacher;
        if (!byCourse[courseKey]) {
            byCourse[courseKey] = {
                name: item.name,
                teacher: item.teacher || null,
                note: null,
                blocks: []
            };
            courseSeen[courseKey] = {};
            courseOrder.push(courseKey);
        }
        var course = byCourse[courseKey];
        var courseSeenKeys = courseSeen[courseKey];
        var runs = runsOf(item.weeks);
        for (var ri = 0; ri < runs.length; ri++) {
            var run = runs[ri];
            var blockKey = item.day + '|' + item.startSection + '|' + item.endSection + '|' +
                run.start + '|' + run.end + '|' + run.weekType + '|' + item.room;
            if (courseSeenKeys[blockKey]) continue;
            courseSeenKeys[blockKey] = true;
            if (run.end > maxWeek) maxWeek = run.end;
            course.blocks.push({
                dayOfWeek: item.day,
                startPeriod: item.startSection,
                endPeriod: item.endSection,
                startWeek: run.start,
                endWeek: run.end,
                weekType: run.weekType,
                location: item.room || null
            });
        }
    }

    // 课程顺序：按排序后第一次出现的排列（不依赖任何不稳定排序）
    for (var ci2 = 0; ci2 < courseOrder.length; ci2++) {
        courses.push(byCourse[courseOrder[ci2]]);
    }

    if (!courses.length) {
        // 两种失败要分清楚：教务说「没课」和「给了课但我们一条都没读懂」，
        // 后者八成是教务系统的结构变了，报「可能还没排课」会把用户和我们一起带偏
        var detail = [];
        if (activities.length) detail.push('解析到 ' + activities.length + ' 个 TaskActivity');
        if (taskActivityCountOf() > 0) detail.push('响应里有 ' + taskActivityCountOf() + ' 个 TaskActivity');
        if (badArgs) detail.push('参数不足 ' + badArgs + ' 个');
        if (badIndex) detail.push('星期或节次超范围 ' + badIndex + ' 处');
        if (noWeeks) detail.push('周次位图为空 ' + noWeeks + ' 个');
        if (noIndex) detail.push('没读到 index 赋值 ' + noIndex + ' 个');
        throw new Error(
            '这个学期没有解析到任何课程（' + (detail.length ? detail.join('、') : '响应里没有课程数据') +
            '）：可能还没排课（假期里常见），也可能登录状态已失效，或者教务系统改了课表页的结构。' +
            '请重新登录、在教务系统里确认能看到课表后再点「提取课表」'
        );
    }

    for (var bi2 = 0; bi2 < courses.length; bi2++) {
        courses[bi2].blocks.sort(function (x, y) {
            return cmpNum(x.dayOfWeek, y.dayOfWeek) || cmpNum(x.startPeriod, y.startPeriod) ||
                cmpNum(x.endPeriod, y.endPeriod) || cmpNum(x.startWeek, y.startWeek) ||
                cmpNum(x.endWeek, y.endWeek) || cmpStr(x.weekType, y.weekType) ||
                cmpStr(x.location || '', y.location || '');
        });
    }

    function taskActivityCountOf() {
        var n = intOf(data.taskActivityCount);
        return n === null || n < 0 ? 0 : n;
    }

    // ---------- 作息时间 ----------
    function stripTags(html) {
        return String(html === null || html === undefined ? '' : html)
            .replace(/<[^>]*>/g, ' ')
            .replace(/&nbsp;|&#160;|&#x0*A0;/gi, ' ')
            .split(NBSP).join(' ');
    }

    // 课表表头的节次时间：树维 EAMS 的节次表头是 th[id="0_N"]，文本形如「第1节 (08:00-08:45)」。
    // 认 id 也认「第N节」文字，两路都读不到就是读不到（调用方回落内置表并出声）
    function slotsFromTable(html) {
        var source = String(html === null || html === undefined ? '' : html);
        var slots = [];
        var seen = {};
        var cellRe = /<t[hd][^>]*>([\s\S]*?)<\/t[hd]>/gi;
        var cell;
        while ((cell = cellRe.exec(source)) !== null) {
            var inner = cell[1];
            var cellText = stripTags(inner);
            var label = /第\s*(\d{1,2})\s*节/.exec(cellText);
            var idMatch = /id\s*=\s*["']0_(\d{1,2})["']/i.exec(cell[0]);
            var period = idMatch ? intOf(idMatch[1]) : (label ? intOf(label[1]) : null);
            if (period === null || period < 1 || period > MAX_WEEK || seen[period]) continue;
            var time = /(\d{1,2}:\d{2})\s*[-—–－~～至到]\s*(\d{1,2}:\d{2})/.exec(cellText);
            if (!time) continue;
            var start = timeOf(time[1]);
            var end = timeOf(time[2]);
            if (!start || !end || minutesOf(start) >= minutesOf(end)) continue;
            seen[period] = true;
            slots.push({ periodIndex: period, start: start, end: end });
        }
        slots.sort(function (x, y) { return cmpNum(x.periodIndex, y.periodIndex); });
        return slots;
    }

    var tableSlots = slotsFromTable(data.tableHtml);
    var slotsFromPage = tableSlots.length > 0;
    var baseSlots = slotsFromPage ? tableSlots : BUILTIN_PERIOD_TIMES;

    // 课表里真正用到的节次
    var usedPeriods = {};
    var maxPeriod = 0;
    for (var pi = 0; pi < courses.length; pi++) {
        for (var bi = 0; bi < courses[pi].blocks.length; bi++) {
            var block = courses[pi].blocks[bi];
            usedPeriods[block.startPeriod] = true;
            usedPeriods[block.endPeriod] = true;
            if (block.endPeriod > maxPeriod) maxPeriod = block.endPeriod;
        }
    }

    var periodTimes = [];
    var covered = {};
    for (var si2 = 0; si2 < baseSlots.length; si2++) {
        periodTimes.push({
            periodIndex: baseSlots[si2].periodIndex,
            start: baseSlots[si2].start,
            end: baseSlots[si2].end
        });
        covered[baseSlots[si2].periodIndex] = true;
    }

    // 作息表里没有、但课表里用到的节次：用空课内建节次表补出来（补了要说）；再没有就如实说没有
    var extendedFrom = 0;
    var extendedTo = 0;
    var uncoveredFrom = 0;
    for (var period = 1; period <= maxPeriod; period++) {
        if (!usedPeriods[period] || covered[period]) continue;
        var filled = -1;
        for (var hi = 0; hi < HOST_PERIOD_TIMES.length; hi++) {
            if (HOST_PERIOD_TIMES[hi].periodIndex === period) filled = hi;
        }
        if (filled >= 0) {
            periodTimes.push({
                periodIndex: period,
                start: HOST_PERIOD_TIMES[filled].start,
                end: HOST_PERIOD_TIMES[filled].end
            });
            covered[period] = true;
            if (!extendedFrom || period < extendedFrom) extendedFrom = period;
            if (period > extendedTo) extendedTo = period;
        } else if (!uncoveredFrom) {
            uncoveredFrom = period;
        }
    }
    periodTimes.sort(function (x, y) { return cmpNum(x.periodIndex, y.periodIndex); });

    // ---------- 开学日 / 总周数 ----------
    var CALENDAR_RE = /开始\s*\/\s*结束日期\s*[：:]?\s*(\d{4})\s*[-/.]\s*(\d{1,2})\s*[-/.]\s*(\d{1,2})\s*(?:~|～|至|到|-|—)\s*(\d{4})\s*[-/.]\s*(\d{1,2})\s*[-/.]\s*(\d{1,2})\s*[（(]\s*(\d+)\s*[)）]/;

    function calendarFromHtml(html) {
        var text = stripTags(html);
        var m = CALENDAR_RE.exec(text);
        if (!m) return null;
        var start = isoFromNumbers(m[1], m[2], m[3]);
        var end = isoFromNumbers(m[4], m[5], m[6]);
        var weeks = intOf(m[7]);
        if (!start || !end || end < start) return null;
        if (weeks === null || weeks < 1) weeks = 0;
        return { start: start, end: end, weeks: weeks };
    }

    // calendar-info 取不到时，extract 可能已经把学期起止日期填进了学期条目
    function calendarFromSemester(entry) {
        if (!entry || typeof entry !== 'object') return null;
        var start = isoFromText(entry.startDate);
        var end = isoFromText(entry.endDate);
        var weeks = intOf(entry.totalWeeks);
        if (!start && !end && !(weeks >= 1)) return null;
        if (start && end && end < start) return null;
        return { start: start, end: end, weeks: weeks === null || weeks < 1 ? 0 : weeks };
    }

    var calendar = calendarFromHtml(data.calendarHtml) || calendarFromSemester(picked) ||
        calendarFromSemester(semester);

    var firstDayOfWeek = intOf(data.firstDayOfWeek);
    if (firstDayOfWeek === null) firstDayOfWeek = intOf(semester.firstDayOfWeek);
    if (firstDayOfWeek === null) firstDayOfWeek = intOf(picked && picked.firstDayOfWeek);
    if (!(firstDayOfWeek >= 1 && firstDayOfWeek <= 7)) firstDayOfWeek = 1;

    var todayIso = isoFromText(data.today) || localTodayIso();
    var firstDay;
    var alignedFrom = null;
    if (calendar && calendar.start) {
        firstDay = isoOnOrBefore(calendar.start, firstDayOfWeek);
        if (firstDay !== calendar.start) alignedFrom = calendar.start;
    } else {
        firstDay = isoOnOrBefore(todayIso, firstDayOfWeek);
    }

    var calendarWeeks = calendar && calendar.weeks >= 1 ? calendar.weeks : 0;
    var calendarWeeksClamped = false;
    if (calendarWeeks > MAX_WEEK) { calendarWeeks = MAX_WEEK; calendarWeeksClamped = true; }
    var totalWeeks = calendarWeeks > 0 ? calendarWeeks : DEFAULT_TOTAL_WEEKS;
    var raisedBySchedule = false;
    if (maxWeek > totalWeeks) { totalWeeks = maxWeek; raisedBySchedule = true; }
    if (totalWeeks > MAX_WEEK) totalWeeks = MAX_WEEK;
    if (totalWeeks < 1) totalWeeks = DEFAULT_TOTAL_WEEKS;

    // ---------- warnings（顺序固定，最要紧的在前） ----------
    warn(
        '只导入了教务系统当前选中的学期（' + termName +
        '）；要导入别的学期，请在教务页面里切到那个学期再点「提取课表」'
    );

    if (termNameFromSchool) {
        warn('学期名教务系统没有给出，已按「' + termName + '」导入，如与实际不符可在学期管理里改名');
    }

    if (calendar && calendar.start) {
        warn('开学日期取自教务系统的学期日历：第一周从 ' + firstDay + ' 开始，请在学期管理里核对');
        if (alignedFrom) {
            warn(
                '学期日历给的起始日 ' + alignedFrom + '（' + weekdayCn(weekdayOfIso(alignedFrom)) +
                '）不是每周起始日（' + weekdayCn(firstDayOfWeek) + '），已回退到那一天所在的' +
                weekdayCn(firstDayOfWeek) + ' ' + firstDay + '，请在学期管理里核对'
            );
        }
    } else {
        warn(
            '开学日期无法从教务获取（学期日历接口没有给出可用的开始/结束日期），已按最近的' +
            weekdayCn(firstDayOfWeek) + '推算（' + firstDay + '），请在学期管理里核对成学校实际开学日'
        );
    }

    if (calendarWeeks > 0) {
        if (raisedBySchedule && !calendarWeeksClamped) {
            warn(
                '学期日历说这个学期 ' + calendarWeeks + ' 周，但课表里有第 ' + maxWeek +
                ' 周的课，已按 ' + totalWeeks + ' 周导入（否则那几周的课放不下），如与实际不符可在学期管理里改'
            );
        } else {
            warn(
                '学期总周数取自教务的学期日历（' + calendarWeeks + ' 周' +
                (calendarWeeksClamped ? '，已按载荷上限 ' + MAX_WEEK + ' 周截断' : '') +
                '），如与实际不符可在学期管理里改'
            );
        }
    } else {
        warn(
            '学期总周数教务没有给出，已按 ' + totalWeeks + ' 周计（课表里最大的周次是 ' + maxWeek +
            ' 周），如校历不同请在学期管理里调整'
        );
    }

    if (slotsFromPage) {
        warn(
            '作息时间取自课表表头的节次时间（共 ' + periodTimes.length + ' 节，第 1 节 ' +
            (periodTimes.length ? periodTimes[0].start + '-' + periodTimes[0].end : '不明') +
            '），如与学校实际作息不符请在节次设置里调整'
        );
    } else {
        warn(
            '课表表头里没有读到节次时间，作息时间用的是适配器内置的' + SCHOOL_NAME + ' ' +
            BUILTIN_PERIOD_TIMES.length + ' 节作息表（第 1 节 ' + BUILTIN_PERIOD_TIMES[0].start + '-' +
            BUILTIN_PERIOD_TIMES[0].end + '），没有向教务核对过，如与学校实际作息不符请在节次设置里调整'
        );
    }
    if (extendedFrom) {
        warn(
            '课表里用到第 ' + maxPeriod + ' 节，而作息表只到第 ' + baseSlots.length +
            ' 节：第 ' + (extendedFrom === extendedTo ? extendedFrom : extendedFrom + '-' + extendedTo) +
            ' 节按空课内建节次表补了时间，请核对'
        );
    }
    if (uncoveredFrom) {
        warn('课表里用到第 ' + uncoveredFrom + ' 节及之后，作息表里没有它们的上下课时间，课表里这几节不会显示时间');
    }

    if (unitCountFromDefault) {
        warn(
            '课表页面里没有读到 unitCount（一天排几节），已按上游脚本的缺省值 ' + DEFAULT_UNIT_COUNT +
            ' 计算星期与节次，请核对课表'
        );
    }
    if (zeroBitCourses > 0) {
        warn(
            '周次位图第 0 位为 1（共 ' + zeroBitCourses + ' 处），与树维 EAMS 的约定（下标 0 是占位符）' +
            '不符，已按忽略处理，请在导入预览里核对周次'
        );
    }
    if (clampedWeeks > 0) {
        warn(
            '有 ' + clampedWeeks + ' 个周次超过载荷上限 ' + MAX_WEEK + ' 周（位图里最大到第 ' + clampedFrom +
            ' 周），已按第 ' + MAX_WEEK + ' 周计算，请核对课表'
        );
    }

    var skipped = badArgs + badIndex + noWeeks + noIndex;
    if (skipped > 0) {
        var reasons = [];
        if (badArgs) reasons.push('缺 TaskActivity 参数 ' + badArgs + ' 条');
        if (badIndex) reasons.push('星期或节次超出范围 ' + badIndex + ' 处');
        if (noWeeks) reasons.push('周次位图里一位都没有 ' + noWeeks + ' 条');
        if (noIndex) reasons.push('没读到 index 赋值 ' + noIndex + ' 条');
        warn(
            '有 ' + skipped + ' 条课程数据不全（' + reasons.join('、') +
            '），已跳过：教务数据不完整时会出现，如发现少课请反馈'
        );
    }

    var reportedActivities = taskActivityCountOf();
    if (reportedActivities > activities.length) {
        warn(
            '教务响应里有 ' + reportedActivities + ' 个 TaskActivity，只解析到 ' + activities.length +
            ' 个（响应可能不完整），请核对课表'
        );
    }

    var warnings = allWarnings;
    if (allWarnings.length > MAX_WARNINGS) {
        warnings = allWarnings.slice(0, MAX_WARNINGS - 1);
        warnings.push('另有 ' + (allWarnings.length - MAX_WARNINGS + 1) + ' 条说明因为超出上限没有显示，请把这份课表反馈给我们');
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
