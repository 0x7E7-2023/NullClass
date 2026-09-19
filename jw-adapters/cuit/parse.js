(function () {
    // 成都信息工程大学教务适配器（树维 EAMS 平台）—— 第二步：教务原始数据 → 空课课表载荷。
    //
    // 移植自 shiguang_warehouse 的 CUIT/cuit_bk_old.js
    //   https://github.com/XingHeYuZhuan/shiguang_warehouse  （MIT，上游作者 igugyj(Pfolg)）
    //   上游快照 e62554a4034386b893bcd6813c7b2b64f8c730a3（2026-09-12）
    //   上游 adapters.yaml：adapter_id "CUIT_02"、adapter_name "成都信息工程大学教务管理系统"、
    //   asset_js_path "cuit_bk_old.js"、import_url "https://jwc.cuit.edu.cn/"
    //   （同目录还有 CUIT_01 = 本科实践教学平台 cuit_bk_new.js，与本件无关，别拿错）
    //
    // 上游在同一段脚本里抓页面、算周次、拼课程、存作息；这里只做纯转换
    // （不碰页面、不发请求，CI 里用 Rhino 实跑），取数全部在 extract.js 里。
    //
    // 移植改动（逐条，与 AUDIT.md 对应）：
    //   ① ES6 → ES5：去掉模板串、箭头函数、块级声明关键字、Set / 扩展运算符；
    //      上游遍历 Map 的写法（for-of + 解构）换成显式遍历。本段本来就没有异步。
    //   ② 位图基准不再照抄上游的 i + 1。上游把「位图 0 位」当成第 1 周，而同族 5 件
    //      （uestc / hpu / hunnu / zua / zzvcae）在代码或注释里都写着「0 位是占位符」。
    //      本批统一口径：位图下标 i 就是第 i 周，0 位不产出周次。详见 AUDIT.md 第 1 节。
    //      上游的 i + 1 与真机核对方法都记在 AUDIT.md 里。
    //   ③ 上游解析学期列表用的动态求值（把响应拼成一段代码再执行）没有移植 —— 本仓明令禁止
    //      任何动态求值，而且那段解析在取数侧：学期列表改由 extract.js 用 JSON.parse 解析后原样交出来。
    //   ④ 学期不再弹窗问用户（上游 showSingleSelection 选学期）：extract.js 自动取当前学期，
    //      这里只认 data.term，拿不到就用「成都信息工程大学 + 学年学期」兜底。
    //   ⑤ 开学日：上游的 semesterCalendar 只拿 id 与名字（起止日期被丢掉）。这里读起止日期，
    //      取学期第一天 → 回退到那一周的周一（手册 4.3）；拿不到就按最近的周一推算，
    //      两种情况都写进 warnings。上游是问用户要日期，这里不保留问用户的路径。
    //   ⑥ 总周数：上游写死 12（那是 unitCount 的缺省，不是周数）。这里用学期起止日期算，
    //      没有就用课表里出现的最晚周次，并按载荷上限 30 截断。
    //   ⑦ 教师 / 教室拿不到一律留空（null）。上游的 join 表达式解析保留（同族 hpu 也这么做）。
    //   ⑧ 位图第 0 位为 1 时写一条 warnings（统一口径要求），不产出「第 0 周」。
    //   ⑨ 位图长度超过学期总周数时不静默丢周次：抬到 30 以内就用，顶到 30 就截断并 warn。
    //   ⑩ 课名：上游只剥末尾括号里形如「10 位数字.2 位数字」的串，其余括号（教学班号、班级）保留；
    //      上游又有一句 courseFull.replace(/\(.*\)/, "") 在剥「课程全称」的括号 —— 那句只在
    //      args[3] 为空时才生效，且会用课程全称当课名。这里保留这个优先级，但把括号剥法
    //      对齐 args[3] 的规则，免得同一门课在两个分支下名字不一样。
    //   ⑪ 上游一行的节次用 Set 去重后并连续区间；这里保留（区间化 + 按节次分块的顺序都写明）。
    //   ⑫ 排序全部换成按码位比较 + 完整次级键，不依赖引擎的排序稳定性（fixture 逐数组比对）。

    var data = JSON.parse(typeof __ncInput === 'undefined' ? '{}' : __ncInput);
    var term = data.term || {};
    var raw = data.raw || {};
    var html = typeof raw.tableHtml === 'string' ? raw.tableHtml : '';

    var SEP = String.fromCharCode(0);   // 复合键分隔符：源码里不出现控制字符、也不出现转义序列
    var MAX_WEEK = 30;                  // 载荷校验：totalWeeks ∈ 1..30，startWeek/endWeek ∈ 1..totalWeeks
    var MAX_PERIOD = 20;                // 单日节次上限：超过它一定是脏数据
    var FALLBACK_UNIT_COUNT = 12;       // 上游 /var unitCount = (\d+);/ 读不到时的缺省值
    var SEMESTER_PREFIX = '成都信息工程大学';   // 学期名兜底用（手册 4.7：别拿适配器名当学期名）
    var MAX_WARNINGS = 20;
    var MAX_WARNING_CHARS = 200;

    // 学校作息（成都信息工程大学）：取自上游 cuit_bk_old.js 里那张 getPresetTimeSlots() 内置表，原样搬过来。
    // 上游没有向教务请求作息，这张表就是脚本作者对学校的了解 —— 载荷里带出去的同时必须在 warnings 里说明。
    var SCHOOL_PERIOD_TIMES = [
        { periodIndex: 1, start: '08:20', end: '09:05' },
        { periodIndex: 2, start: '09:15', end: '10:00' },
        { periodIndex: 3, start: '10:20', end: '11:05' },
        { periodIndex: 4, start: '11:15', end: '12:00' },
        { periodIndex: 5, start: '14:00', end: '14:45' },
        { periodIndex: 6, start: '14:55', end: '15:40' },
        { periodIndex: 7, start: '15:50', end: '16:35' },
        { periodIndex: 8, start: '16:45', end: '17:30' },
        { periodIndex: 9, start: '17:40', end: '18:25' },
        { periodIndex: 10, start: '19:30', end: '20:15' },
        { periodIndex: 11, start: '20:25', end: '21:10' },
        { periodIndex: 12, start: '21:20', end: '22:05' }
    ];

    // 空课内置节次表（12 节）：课表用到的节次超出学校作息表时用它把缺的节次补出来并写进 warnings。
    // 数这份表只为了算「补到第几节」，具体时间交给应用（载荷不写 periodTimes，应用会用同一张表）。
    var BUILTIN_PERIOD_COUNT = 12;

    // 推算开学日的锚点：第一学期多在 9 月初，第二学期多在 2 月下旬。别把锚点当 firstDay，
    // 手册 4.3 要求回退到「第 1 周的第一天」那个周一（含当天）。
    var KIND_CN = { first: '一', second: '二', third: '三' };
    var KIND_ANCHOR = {
        first: { month: 9, day: 1, rule: '第一学期 = 9 月 1 日所在周的周一' },
        second: { month: 2, day: 20, rule: '第二学期 = 2 月 20 日所在周的周一' },
        third: { month: 7, day: 1, rule: '夏季学期 = 7 月 1 日所在周的周一' }
    };

    // ---------- 小工具 ----------
    function text(value) {
        if (value === null || value === undefined) return '';
        return String(value).replace(/\s+/g, ' ').trim();
    }

    function digits(value) {
        var s = text(value);
        return /^[0-9]+$/.test(s) ? s : '';
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

    // 该日期所在周的周一（用 UTC 算，避免时区把日期挪一天）
    function mondayOnOrBefore(year, month, day) {
        var date = new Date(Date.UTC(year, month - 1, day));
        var offset = (date.getUTCDay() + 6) % 7;
        return new Date(date.getTime() - offset * 86400000);
    }

    // 任意形态的日期 → ISO。先按开头认（"2026-09-07 00:00:00" 这种要取前一半），
    // 认不到再在串里找（"2026-09-07至2027-01-17" / "第1周 2026-09-07" 这类）。
    function isoOfAny(value) {
        var s = text(value);
        var m = /^([0-9]{4})[-/.]([0-9]{1,2})[-/.]([0-9]{1,2})/.exec(s);
        if (!m) m = /([0-9]{4})[-/.]([0-9]{1,2})[-/.]([0-9]{1,2})/.exec(s);
        if (!m) return null;
        var month = parseInt(m[2], 10);
        var day = parseInt(m[3], 10);
        if (month < 1 || month > 12 || day < 1 || day > 31) return null;
        return m[1] + '-' + pad2(month) + '-' + pad2(day);
    }

    function mondayOfIso(iso) {
        if (!iso) return null;
        return isoOf(mondayOnOrBefore(
            parseInt(iso.substring(0, 4), 10),
            parseInt(iso.substring(5, 7), 10),
            parseInt(iso.substring(8, 10), 10)
        ));
    }

    function localTodayIso() {
        var now = new Date();
        return now.getFullYear() + '-' + pad2(now.getMonth() + 1) + '-' + pad2(now.getDate());
    }

    function dayNumber(iso) {
        return Math.round(Date.UTC(
            parseInt(iso.substring(0, 4), 10),
            parseInt(iso.substring(5, 7), 10) - 1,
            parseInt(iso.substring(8, 10), 10)
        ) / 86400000);
    }

    // 确定性的比较函数：不用 localeCompare（它排中文的结果跟引擎有关，而 fixture 是逐数组比对的）
    function cmpStr(a, b) {
        if (a === b) return 0;
        return a < b ? -1 : 1;
    }

    function cmpNum(a, b) {
        return a === b ? 0 : (a < b ? -1 : 1);
    }

    // ---------- 学期名与学期序号 ----------
    // 树维的学期下拉框里是「第一学期 / 第二学期」；上游把它拼成「学年 + 名字 + 学期」。
    function termKind() {
        var label = text(term.name) + ' ' + text(term.kind);
        if (label.indexOf('二') >= 0) return 'second';
        if (label.indexOf('三') >= 0) return 'third';
        return 'first';
    }

    function yearLabel() {
        var schoolYear = text(term.schoolYear);
        if (/^[0-9]{4}-[0-9]{4}$/.test(schoolYear)) return schoolYear;
        if (/^[0-9]{4}$/.test(schoolYear)) return schoolYear + '-' + (parseInt(schoolYear, 10) + 1);
        return schoolYear;
    }

    // 学期名：教务给了就用教务的（含「2026-2027学年第一学期」这种整串），拿不到就用「学校名 + 学年学期」
    function termName() {
        var explicit = text(term.name);
        if (explicit && explicit.indexOf('学期') >= 0) return explicit;
        var year = yearLabel();
        var tail = '第' + KIND_CN[termKind()] + '学期';
        if (explicit) tail = explicit + tail;
        if (year) return year + '学年' + tail;
        return SEMESTER_PREFIX + tail;
    }

    // ---------- 上游的 splitJsArgs（原样移植：引号 / 转义 / 逗号分参数） ----------
    function splitJsArgs(argsText) {
        var args = [];
        var curr = '';
        var inQuote = '';
        var escaped = false;
        var i;
        for (i = 0; i < argsText.length; i++) {
            var ch = argsText.charAt(i);
            if (escaped) { curr += ch; escaped = false; continue; }
            if (ch === '\\') { curr += ch; escaped = true; continue; }
            if (inQuote) { curr += ch; if (ch === inQuote) inQuote = ''; continue; }
            if (ch === '"' || ch === "'") { curr += ch; inQuote = ch; continue; }
            if (ch === ',') { args.push(curr.replace(/^\s+|\s+$/g, '')); curr = ''; continue; }
            curr += ch;
        }
        if (curr.replace(/^\s+|\s+$/g, '') || argsText.charAt(argsText.length - 1) === ',') {
            args.push(curr.replace(/^\s+|\s+$/g, ''));
        }
        return args;
    }

    // ---------- 上游的 unquoteJsLiteral（原样移植，不用 String.prototype.startsWith 这种 ES6 方法） ----------
    function unquoteJsLiteral(token) {
        var s = text(token);
        if (!s) return '';
        if (s === 'null' || s === 'undefined') return '';
        var first = s.charAt(0);
        var last = s.charAt(s.length - 1);
        if (s.length >= 2 && (first === '"' || first === "'") && last === first) {
            var body = s.substring(1, s.length - 1);
            // 上游原样返回不反转义；这里把最常见的两种反转义出来（课名里的反斜杠引号）
            var out = '';
            var i;
            for (i = 0; i < body.length; i++) {
                var ch = body.charAt(i);
                if (ch === '\\' && i + 1 < body.length) {
                    var next = body.charAt(i + 1);
                    if (next === first || next === '\\') { out += next; i++; continue; }
                }
                out += ch;
            }
            return out;
        }
        return s;
    }

    // 上游 cleanCourseName：只剥末尾括号里形如「10 位数字.2 位数字」的教学班编号
    function cleanCourseName(name) {
        return String(name || '').replace(/\([0-9]{10}\.[0-9]{2}\)\s*$/, '').trim();
    }

    // 上游在 args[3] 为空时用课程全称当课名（courseFull.replace(/\(.*\)/,"")，剥掉第一个括号段）。
    // 括号剥法对齐 cleanCourseName 的语义：只剥「括号里全是数字与点」的那一段，别把「(卓越班)」这种
    // 真名字剥掉 —— 上游那句会把「计算机网络(卓越班)」变成「计算机网络」，这里保留完整名。
    function courseNameFromFull(full) {
        var s = text(full);
        if (!s) return '';
        return s.replace(/\([0-9]{10}\.[0-9]{2}\)\s*$/, '').trim();
    }

    // ---------- 读数（上游 parseCoursesFromHtml 的第一步） ----------
    function readUnitCount(source) {
        var m = /\bvar\s+unitCount\s*=\s*(\d+)\s*;/.exec(source);
        if (!m) return null;
        var n = parseInt(m[1], 10);
        return n >= 1 && n <= 31 ? n : null;
    }

    // 教师块（var teachers = [...]; var actTeachers = [...];）—— 上游按「块起点在 activity 之前」
    // 找最近的一块。上游的正则用了 /s（dotAll）与 /g 一起，Rhino 1.8 的 RegExp 不支持 flags 里的 s
    // （那是 ES2018），会把带 s 的字面量直接判语法错误 —— 所以这里用 [\s\S] 代替 dotAll，
    // 与同族 hpu 的做法一致（hpu 也用 [^] / [\s\S]）。这是移植必须做的改写，语义不变。
    function readTeacherBlocks(source) {
        var blocks = [];
        var re = /var\s+teachers\s*=\s*\[([\s\S]*?)\];\s*var\s+actTeachers\s*=\s*\[([\s\S]*?)\];/g;
        var m;
        while ((m = re.exec(source)) !== null) {
            var names = [];
            var nameRe = /name\s*:\s*(?:"([^"]*)"|'([^']*)')/g;
            var nm;
            while ((nm = nameRe.exec(m[2] || m[1] || '')) !== null) {
                var name = text(nm[1] || nm[2] || '');
                if (name) names.push(name);
            }
            if (names.length > 0) {
                blocks.push({ startIndex: m.index, teacherNames: names.join(',') });
            }
        }
        return blocks;
    }

    function teacherBefore(blocks, position) {
        var found = '';
        var i;
        for (i = blocks.length - 1; i >= 0; i--) {
            if (blocks[i].startIndex < position) { found = blocks[i].teacherNames; break; }
        }
        return found;
    }

    // ---------- 周次位图（本件最要紧的一处，见 AUDIT.md 第 1 节） ----------
    // 统一口径：位图下标 i 就是第 i 周，下标 0 是占位符。
    //   bitmap[i] === '1' 且 i >= 1 → 第 i 周；
    //   bitmap[0] === '1' → 不产出周次，写一条 warnings。
    // 上游 cuit_bk_old.js 写的是 weeks.push(i + 1)（把 0 位当第 1 周），同族 5 件写的是本文口径，
    // 本件按统一口径实现。真机核对：若发现整体差一周，把下面这处的 i 改成 i + 1 即可。
    // 位图常见 50-54 位，算出来能到第 54 周，而载荷上限是 30 周：超过的一律在上层丢掉并 warn。
    var MAX_BITMAP_WEEK = 60;   // 位图本身的上限：再长一定是脏数据（54 位是常见长度）

    function weeksOfBitmap(bitmap) {
        var s = String(bitmap === null || bitmap === undefined ? '' : bitmap);
        var weeks = [];
        var placeholder = false;
        var i;
        for (i = 0; i < s.length; i++) {
            if (s.charAt(i) !== '1') continue;
            if (i === 0) { placeholder = true; continue; }
            if (i > MAX_BITMAP_WEEK) continue;
            weeks.push(i);
        }
        return { weeks: weeks, placeholder: placeholder };
    }

    // 周次集合 → 极大段（手册 4.1）：步长 1 视作每周，步长 2 视作单周 / 双周。
    // 一个上游 course 切出多段就写成多个 block，语义等价（我们的 blocks 本来就是列表）。
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

    // ---------- 核心解析 ----------
    var placeholderBitmap = 0;   // 位图第 0 位为 1 的课程数（统一口径要求）
    var unparsedIndex = 0;       // index 表达式认不出的赋值语句数
    var dayOutOfRange = 0;       // index 算出的星期超出 1..7 的赋值语句数
    var noSections = 0;          // 有课但没有一条可用 index 的课程数
    var noWeeks = 0;             // 周次位图全是 0（或超出 30 周）的课程数
    var emptyName = 0;           // 课名读不出来的课程数
    var blockCount = 0;          // 有 index 赋值块但没有读到 activity 的块数

    function parseCourses(source) {
        var unitCountRaw = readUnitCount(source);
        var unitCount = unitCountRaw === null ? FALLBACK_UNIT_COUNT : unitCountRaw;

        var teacherBlocks = readTeacherBlocks(source);

        // 课表 HTML 里课程是内嵌的 JS 块：
        //   activity = new TaskActivity("", teacher, courseFull, courseName, "", room, weeks, ...);
        //   index = 星期 * unitCount + 节次;   （一门课可以有多条）
        // 上游的块正则是 /activity\s*=\s*new\s+TaskActivity\(([^]*?)\)\s*;([\s\S]*?)(?=activity\s*=\s*new\s+TaskActivity|$)/g
        //   —— [^]*? 是「任意字符」的非贪婪写法（上游笔误，能跑但不是它的本意），
        //      末尾的 $ 又没有 m 标志、只匹配「字符串结尾 or 尾部换行」，最后一块可能被吃掉。
        // 这里用「先找全部 new TaskActivity( 的位置，再按位置切块」的写法，行为更好懂：
        // 每块的正文 = 本条 activity 之后、下一条 activity 之前的那一段（最后一块到结尾），
        // 与上游「index 归属于它前面最近的 activity」的语义一致（同族 uestc 也是这么切的）。
        var starts = [];
        var finder = /new\s+TaskActivity\s*\(/g;
        var hit;
        while ((hit = finder.exec(source)) !== null) {
            starts.push({ at: hit.index, open: hit.index + hit[0].length });
        }

        var courses = [];
        var b;
        for (b = 0; b < starts.length; b++) {
            var open = starts[b].open;
            var argsText = readBalanced(source, open);
            if (argsText === null) { blockCount++; continue; }
            var afterBlock = source.substring(argsText.end, b + 1 < starts.length ? starts[b + 1].at : source.length);

            var args = splitJsArgs(argsText.text);
            if (args.length < 7) { blockCount++; continue; }

            var teacherExpr = args[1];
            var courseFull = unquoteJsLiteral(args[2]);
            var courseNameRaw = unquoteJsLiteral(args[3]);
            var classroom = text(unquoteJsLiteral(args[5])).replace(/\s+/g, ' ').trim();
            var weekBitmap = unquoteJsLiteral(args[6]);

            // args[3] 是课程名，args[2] 是课程全称（同族只有本件用 args[2]）。空的话回落到全称。
            var courseName = cleanCourseName(courseNameRaw || courseNameFromFull(courseFull));
            if (!courseName) { emptyName++; continue; }

            var bitmap = weeksOfBitmap(weekBitmap);
            if (bitmap.placeholder) placeholderBitmap++;
            var weeks = bitmap.weeks;
            if (weeks.length === 0) { noWeeks++; continue; }

            var teacherNames = '';
            var teacherExprStr = text(teacherExpr);
            if (teacherExprStr.indexOf('join') >= 0 || teacherExprStr.indexOf('actTeacherName') >= 0) {
                teacherNames = teacherBefore(teacherBlocks, starts[b].at);
            } else {
                teacherNames = unquoteJsLiteral(teacherExpr);
            }

            var sections = [];
            var indexRe = /index\s*=\s*([^;]+);/g;
            var idxMatch;
            var sawIndex = false;
            while ((idxMatch = indexRe.exec(afterBlock)) !== null) {
                var expr = idxMatch[1];
                var day;
                var section;
                var m1 = /^\s*([0-9]+)\s*\*\s*unitCount\s*\+\s*([0-9]+)\s*$/.exec(expr);
                if (m1) {
                    day = parseInt(m1[1], 10) + 1;
                    section = parseInt(m1[2], 10) + 1;
                } else {
                    // 上游还认一种写法：index 直接写成算好的裸数字。同族 hpu 也认这一支，
                    // 而 cuit 上游（正则只写了带 unitCount 的形式）遇到它会整门课丢掉 ——
                    // 这里补上裸数字分支（线性下标 → 星期 = 下标整除 unitCount，节次 = 余数）。
                    var m2 = /^\s*([0-9]+)\s*$/.exec(expr);
                    if (!m2) { unparsedIndex++; continue; }
                    var linear = parseInt(m2[1], 10);
                    day = Math.floor(linear / unitCount) + 1;
                    section = (linear % unitCount) + 1;
                }
                if (!(day >= 1 && day <= 7)) { dayOutOfRange++; continue; }
                if (!(section >= 1 && section <= MAX_PERIOD)) continue;
                sawIndex = true;
                sections.push({ day: day, section: section });
            }
            if (!sawIndex) { noSections++; continue; }

            courses.push({
                name: courseName,
                teacher: teacherNames,
                position: classroom,
                weeks: weeks,
                sections: sections
            });
        }

        return {
            courses: courses,
            unitCount: unitCount,
            unitCountSource: unitCountRaw === null ? 'fallback' : 'page'
        };
    }

    // 从 open（"(" 之后的位置）读到配对的 ")"，返回 {text, end}；读不到返回 null。
    // 引号里的括号不计数（同族 uestc 也按深度找配对，但没处理引号，这里补上）。
    function readBalanced(source, open) {
        var depth = 0;
        var inQuote = '';
        var escaped = false;
        var i;
        for (i = open; i < source.length; i++) {
            var ch = source.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (ch === '\\') { escaped = true; continue; }
            if (inQuote) { if (ch === inQuote) inQuote = ''; continue; }
            if (ch === '"' || ch === "'") { inQuote = ch; continue; }
            if (ch === '(') { depth++; continue; }
            if (ch === ')') {
                if (depth === 0) return { text: source.substring(open, i), end: i + 1 };
                depth--;
            }
        }
        return null;
    }

    // 同一门课（名字 / 教师 / 教室 / 星期 / 周次全同）的节次并成连续区间（上游第三步）。
    // 上游用 Map 的插入序做去重键，这里换成数组 + 显式的键（Rhino 里 Map 有，但显式更稳）。
    function mergeSections(courses) {
        var order = [];
        var byKey = {};
        var i;
        for (i = 0; i < courses.length; i++) {
            var course = courses[i];
            var key = course.name + SEP + course.teacher + SEP + course.position + SEP + course.weeks.join(',');
            if (!byKey[key]) {
                byKey[key] = {
                    name: course.name,
                    teacher: course.teacher,
                    position: course.position,
                    weeks: course.weeks,
                    seen: {},
                    sections: []
                };
                order.push(key);
            }
            var bucket = byKey[key];
            var s;
            for (s = 0; s < course.sections.length; s++) {
                var at = course.sections[s];
                var slotKey = at.day + ':' + at.section;
                if (bucket.seen[slotKey]) continue;
                bucket.seen[slotKey] = true;
                bucket.sections.push(at);
            }
        }

        var merged = [];
        for (i = 0; i < order.length; i++) {
            var item = byKey[order[i]];
            item.sections.sort(function (a, b) {
                return cmpNum(a.day, b.day) || cmpNum(a.section, b.section);
            });
            var day = 0;
            var start = 0;
            var end = 0;
            var k;
            for (k = 0; k < item.sections.length; k++) {
                var cur = item.sections[k];
                if (cur.day !== day) {
                    if (day) merged.push(entry(item, day, start, end));
                    day = cur.day;
                    start = cur.section;
                    end = cur.section;
                    continue;
                }
                if (cur.section === end + 1) { end = cur.section; continue; }
                merged.push(entry(item, day, start, end));
                start = cur.section;
                end = cur.section;
            }
            if (day) merged.push(entry(item, day, start, end));
        }
        return merged;
    }

    function entry(item, day, startSection, endSection) {
        return {
            name: item.name,
            teacher: item.teacher,
            position: item.position,
            weeks: item.weeks,
            day: day,
            startSection: startSection,
            endSection: endSection
        };
    }

    // ---------- 学期起止日期与开学日 ----------
    function semesterStartIso() {
        var start = isoOfAny(term.startDate);
        if (start) return start;
        return isoOfAny(term.dateBegin);
    }

    function semesterEndIso() {
        var end = isoOfAny(term.endDate);
        if (end) return end;
        return isoOfAny(term.dateEnd);
    }

    // 学期天数 → 周数（首尾都算在内）。日期本来是教务给的，算错一周比猜一个数更严重，
    // 所以这里只在两个日期都读得出来、且区间合理（1..400 天）时才用。
    function weeksBetween(startIso, endIso) {
        if (!startIso || !endIso) return 0;
        var days = dayNumber(endIso) - dayNumber(startIso) + 1;
        if (!(days >= 1 && days <= 400)) return 0;
        return Math.ceil(days / 7);
    }

    function estimateStart(todayIso) {
        var anchor = KIND_ANCHOR[termKind()];
        var year = /^[0-9]{4}/.test(text(term.schoolYear)) ? parseInt(text(term.schoolYear).substring(0, 4), 10) : 0;
        var kind = termKind();
        if (!year) {
            // 学年读不出来时退回「今天的周一」。这条分支依赖当天日期，不写进 fixture 用例
            return { iso: mondayOfIso(todayIso) || todayIso, rule: '今天的周一' };
        }
        return {
            iso: isoOf(mondayOnOrBefore(year + (kind === 'first' ? 0 : 1), anchor.month, anchor.day)),
            rule: anchor.rule
        };
    }

    // 校历给的第 1 周日期必须落在该学期锚点附近（±45 天）。接口忽略参数、回了别的学期时，
    // 宁可按推算走 + 说出来，也不要静默写一个错日期 —— 开学日错了整学期的课都会错位。
    function plausibleStart(iso) {
        var kind = termKind();
        var base = /^[0-9]{4}/.test(text(term.schoolYear)) ? parseInt(text(term.schoolYear).substring(0, 4), 10) : 0;
        if (!base || !iso) return true;
        var anchor = KIND_ANCHOR[kind];
        var target = Date.UTC(kind === 'first' ? base : base + 1, anchor.month - 1, anchor.day);
        var value = Date.UTC(
            parseInt(iso.substring(0, 4), 10),
            parseInt(iso.substring(5, 7), 10) - 1,
            parseInt(iso.substring(8, 10), 10)
        );
        if (isNaN(target) || isNaN(value)) return true;
        return Math.abs(value - target) <= 45 * 86400000;
    }

    // ---------- 主流程 ----------
    var parsed = parseCourses(html);
    var warnings = [];

    function warn(message) {
        var line = String(message);
        if (line.length > MAX_WARNING_CHARS) line = line.substring(0, MAX_WARNING_CHARS - 1) + '…';
        warnings.push(line);
    }

    if (!parsed.courses.length) {
        if (html) {
            throw new Error(
                '教务系统返回的课表页面里没有解析到课程：可能是这个学期还没排课（假期里常见），' +
                '也可能登录状态已失效、或教务系统改了课表页。请重新登录、打开课表页确认能看到课后再点「提取课表」'
            );
        }
        throw new Error(
            '没有取到课表数据：请先在教务系统里打开课表页（当前学期），再点「提取课表」'
        );
    }

    var merged = mergeSections(parsed.courses);

    // 课程顺序按首次出现的次序；block 按（星期、起始节、结束节、起始周、结束周、单双周、教室）排 —— 都是确定的
    var courseOrder = [];
    var courseByKey = {};
    var i;
    for (i = 0; i < merged.length; i++) {
        var row = merged[i];
        var key = row.name + SEP + row.teacher;
        if (!courseByKey[key]) {
            courseByKey[key] = { name: row.name, teacher: row.teacher, note: null, blocks: [], seen: {} };
            courseOrder.push(key);
        }
    }

    var maxWeek = 0;
    var maxPeriod = 0;
    var droppedWeeks = 0;
    for (i = 0; i < merged.length; i++) {
        var item = merged[i];
        var course = courseByKey[item.name + SEP + item.teacher];
        if (!course) continue;
        var weeks = item.weeks;
        var w;
        for (w = 0; w < weeks.length; w++) {
            if (weeks[w] > maxWeek) maxWeek = weeks[w];
        }
        if (item.endSection > maxPeriod) maxPeriod = item.endSection;
        var runs = runsOf(weeks);
        var r;
        for (r = 0; r < runs.length; r++) {
            var run = runs[r];
            if (run.end > MAX_WEEK) {
                droppedWeeks++;
                if (run.start > MAX_WEEK) continue;
                run.end = MAX_WEEK;
            }
            var blockKey = item.day + '|' + item.startSection + '|' + item.endSection + '|' +
                run.start + '|' + run.end + '|' + run.weekType + '|' + item.position;
            if (course.seen[blockKey]) continue;
            course.seen[blockKey] = true;
            course.blocks.push({
                dayOfWeek: item.day,
                startPeriod: item.startSection,
                endPeriod: item.endSection,
                startWeek: run.start,
                endWeek: run.end,
                weekType: run.weekType,
                location: item.position ? item.position : null
            });
        }
    }

    var courses = [];
    for (i = 0; i < courseOrder.length; i++) {
        var built = courseByKey[courseOrder[i]];
        built.blocks.sort(function (a, b) {
            return cmpNum(a.dayOfWeek, b.dayOfWeek) || cmpNum(a.startPeriod, b.startPeriod) ||
                cmpNum(a.endPeriod, b.endPeriod) || cmpNum(a.startWeek, b.startWeek) ||
                cmpNum(a.endWeek, b.endWeek) || cmpStr(a.weekType, b.weekType) ||
                cmpStr(a.location || '', b.location || '');
        });
        courses.push({ name: built.name, teacher: built.teacher || null, note: null, blocks: built.blocks });
    }

    var name0 = termName();    var todayIso = isoOfAny(data.today) || localTodayIso();

    var startIso = semesterStartIso();
    var endIso = semesterEndIso();
    var calendarWeeks = weeksBetween(startIso, endIso);
    var startRejected = null;
    if (startIso && !plausibleStart(startIso)) {
        // 起止日期整体不可信：开学日与总周数一起退回推算 / 课表，别只丢一半
        startRejected = startIso + (endIso ? ' 至 ' + endIso : '');
        startIso = null;
        endIso = null;
        calendarWeeks = 0;
    }
    var semesterWeeks = calendarWeeks;
    var capped = false;
    if (semesterWeeks > MAX_WEEK) { semesterWeeks = MAX_WEEK; capped = true; }

    var start;
    if (startIso) {
        start = { iso: mondayOfIso(startIso), rule: '教务学期起止日期' };
    } else {
        start = estimateStart(todayIso);
    }

    // 总周数：优先学期起止日期；拿不到就退到课表里出现的最晚周次（再没有就用课表条数无关的缺省 18）。
    // 课表里更晚的周次必须放得下，否则那些 block 会越界、整包被校验拒掉 —— 这种情况抬上去并说明。
    var totalWeeks = semesterWeeks > 0 ? semesterWeeks : (maxWeek > 0 ? maxWeek : 18);
    var weeksSource = semesterWeeks > 0
        ? ('教务的学期起止日期（' + startIso + ' 至 ' + endIso + '，' + semesterWeeks + ' 周）')
        : ('适配器按课表最晚周次推算的 ' + totalWeeks + ' 周（教务没有给出学期起止日期）');
    var raised = false;
    var clamped = false;
    if (maxWeek > totalWeeks) { totalWeeks = maxWeek; raised = true; }
    if (totalWeeks > MAX_WEEK) { totalWeeks = MAX_WEEK; clamped = true; }

    // 节次表：载荷不写 periodTimes。上游是「先 savePresetTimeSlots 存内置作息，再导入课程」，
    // 也就是作息与课程是两份独立的东西；这里把内置作息当核对说明交出去，
    // 应用缺省用同一形状的 12 节表（第 1-4 节上午 / 5-8 节下午 / 9-12 节晚上，与本校作息同形）。
    var tableLength = SCHOOL_PERIOD_TIMES.length;
    var badSlots = 0;
    for (i = 0; i < tableLength; i++) {
        var slotStart = /^([01]?[0-9]|2[0-3]):[0-5][0-9]$/.exec(SCHOOL_PERIOD_TIMES[i].start);
        var slotEnd = /^([01]?[0-9]|2[0-3]):[0-5][0-9]$/.exec(SCHOOL_PERIOD_TIMES[i].end);
        if (!slotStart || !slotEnd) badSlots++;
    }
    var uncoveredFrom = maxPeriod > tableLength ? tableLength + 1 : 0;

    // ---------- warnings（顺序固定；上限 20 条 / 每条 200 字） ----------
    warn(
        '只导入了教务系统当前选中的学期（' + name0 +
        '）；要导入别的学期，请在教务页面里切到那个学期再点「提取课表」'
    );

    if (startRejected) {
        warn(
            '教务给出的学期起止日期（' + startRejected + '）不属于这个学期，已忽略；第 1 周按「' +
            start.rule + '」推算为 ' + start.iso + '，开学日期与总周数都请在学期管理里核对'
        );
    } else if (startIso && calendarWeeks > 0) {
        warn(
            '开学日期取自教务的学期起止日期：第 1 周从 ' + start.iso + ' 开始，请在学期管理里核对'
        );
    } else {
        warn(
            '教务没有给出学期的起止日期，第 1 周按「' + start.rule + '」推算为 ' + start.iso +
            '，请在学期管理里核对成学校实际开学日'
        );
    }

    if (raised) {
        warn(
            '学期总周数用的是' + weeksSource + '，但课表里有第 ' + maxWeek + ' 周的课，已按 ' +
            totalWeeks + ' 周导入（否则那几周的课放不下），如与实际不符可在学期管理里改'
        );
    } else {
        warn(
            '学期总周数用的是' + weeksSource +
            (capped ? '，已按载荷上限 ' + MAX_WEEK + ' 周截断' : '') +
            '，如与实际不符可在学期管理里改'
        );
    }

    warn(
        '作息时间用的是适配器内置的成都信息工程大学 ' + tableLength +
        ' 节作息表（第 1 节 ' + SCHOOL_PERIOD_TIMES[0].start + '-' + SCHOOL_PERIOD_TIMES[0].end +
        '），不是从教务页面读的，请对照教务处公布的作息核对'
    );

    warn(
        '课表的上下课时间用的是空课内置的 12 节时间表（1-4 节上午、5-8 节下午、9-12 节晚上），' +
        '与学校实际作息（第 1 节 08:20 开始）可能不一致，可在学期管理里改'
    );

    if (parsed.unitCountSource === 'fallback') {
        warn(
            '课表页面里没有读到节次数（unitCount），已按缺省 ' + parsed.unitCount +
            ' 节换算课表的行；若发现课程落在错误的节次上，请反馈'
        );
    }

    warn(
        '周次按教务的周次位图读：位图第 1 位对应第 1 周、第 0 位是占位符（本适配器按同族统一口径处理，' +
        '不采用上游脚本的「第 0 位 = 第 1 周」写法），请在导入预览里核对周次'
    );

    if (placeholderBitmap > 0) {
        warn(
            '有 ' + placeholderBitmap + ' 门课的周次位图第 0 位是 1，与同族约定的占位符不符，已按忽略处理，' +
            '请在导入预览里核对周次'
        );
    }

    if (unparsedIndex > 0) {
        warn(
            '有 ' + unparsedIndex + ' 处课表坐标（index = 星期 * unitCount + 节次）认不出来，' +
            '对应的节次没有导入，请反馈'
        );
    }
    if (dayOutOfRange > 0) {
        warn('有 ' + dayOutOfRange + ' 处课表坐标算出的星期超出周一至周日，已丢弃');
    }
    if (blockCount > 0) {
        warn(
            '有 ' + blockCount + ' 块课表脚本（TaskActivity）没有读到完整参数，相关课程已跳过，教务改版时会出现，请反馈'
        );
    }
    if (noSections > 0) {
        warn('有 ' + noSections + ' 门课在课表里没有可用的节次坐标（只有周次），已跳过，请反馈');
    }
    if (noWeeks > 0) {
        warn(
            '有 ' + noWeeks + ' 门课的周次位图是空的（或全部超出 1-' + MAX_WEEK + ' 周），已跳过：' +
            '教务没排周次时会出现，如发现少课请反馈'
        );
    }
    if (emptyName > 0) {
        warn('有 ' + emptyName + ' 门课在读到的课表脚本里没有课程名，已跳过，请反馈');
    }
    if (droppedWeeks > 0) {
        warn('有 ' + droppedWeeks + ' 段周次超出 1-' + MAX_WEEK + ' 周，已截断到 ' + MAX_WEEK + ' 周');
    }
    if (clamped && !raised) {
        warn('学期总周数超过载荷上限，已按 ' + MAX_WEEK + ' 周截断');
    }
    if (uncoveredFrom > 0) {
        warn(
            '课表里用到第 ' + maxPeriod + ' 节，而内置作息表只到第 ' + tableLength + ' 节：' +
            '超出部分的时间交给应用的内建时间表，请核对作息'
        );
    }
    if (badSlots > 0) {
        warn('适配器内置作息表里有 ' + badSlots + ' 节的时间不合法，请反馈');
    }

    var finalWarnings = warnings;
    if (warnings.length > MAX_WARNINGS) {
        finalWarnings = warnings.slice(0, MAX_WARNINGS - 1);
        finalWarnings.push(
            '另有 ' + (warnings.length - MAX_WARNINGS + 1) + ' 条说明因为超出上限没有显示，请把这份课表反馈给我们'
        );
    }

    return JSON.stringify({
        specVersion: 1,
        kind: 'schedule',
        ocrAssisted: false,
        warnings: finalWarnings,
        terms: [
            {
                name: name0,
                firstDay: start.iso,
                totalWeeks: totalWeeks,
                periodTimes: [],
                courses: courses
            }
        ]
    });
})()
