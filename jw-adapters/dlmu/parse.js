(function () {
    // 大连海事大学教务适配器（树维 EAMS 平台）—— 第二步：纯转换（不碰页面、不发请求）。
    //
    // 移植自 shiguang_warehouse 的 DLMU/dlmu_01.js
    //   https://github.com/XingHeYuZhuan/shiguang_warehouse  （MIT，作者 whynusn）
    //   上游快照 e62554a4034386b893bcd6813c7b2b64f8c730a3（2026-09-12）
    //
    // 输入（extract.js 交出来的原始数据，一个 JSON 字符串）：
    //   {
    //     "today": "2026-09-16",                     // 可选：取数当天（只用于推算；缺省用本机日期）
    //     "term": { "id", "kind", "schoolYear",      // 学期线索。kind = 树维学期对象的 name 字段
    //               "label", "name", "startDate",    //（通常是 "1"/"2"/"3"）；label = 下拉框文本；
    //               "weekCount" },                   // startDate / weekCount 不一定有
    //     "unitCountPage": 10,                       // 课表页里的 var unitCount（读不到为 null）
    //     "ids": "2220260001",                       // 课表接口用的学号
    //     "tableHtml": "<html>…</html>"              // courseTableForStd!courseTable.action 的响应全文
    //   }
    // 输出：空课课表载荷（specVersion 1 / kind schedule）。取数全部在 extract.js 里。
    //
    // 移植改动（逐条对照移植手册 §4 与本批 12 条检查表；完整版见本目录 AUDIT.md）：
    //   ① **拆掉动态代码求值**（本件的核心改动）。上游里那个求 index 值的小函数是三步：
    //        1) 把表达式里的 unitCount 换成数字、并去掉所有空白；
    //        2) 用 JS 的函数构造器把「return <表达式>」当代码编译并执行，返回结果；
    //        3) 出错就 return 0。
    //      注释自称「仅允许数字和基本运算符」，但**代码里没有任何校验** —— 表达式是从**网络取回的
    //      课表 HTML** 里正则抓出来的任意字符串，直接进函数构造器，命中移植手册 §5 第 6 条。
    //      这里换成正则解析（readIndex）：两种形态各自用自己的捕获组算，认不出来返回 null，绝不求值。
    //   ② 认不出来的 index **不静默算成 0**：上游 catch 里 return 0，会把那条排课排到
    //      「周一第 1 节」；这里丢弃那条排课、计数进 warnings，并把畸形的样本带出去。
    //   ③ 周次位图：上游的循环下标从 0 起、见到字面字符 1 就把下标 push 进周次数组，
    //      **没有跳过 0 位**，位图第 0 位为 1 时会产出「第 0 周」（载荷校验也会拒）。按本批统一口径
    //      改成下标 i >= 1 才产出周次，0 位为 1 时写一条 warnings（同族 5 件的约定：下标 i 就是第 i 周）。
    //   ④ 节次数 unitCount：上游是常量 10（注释「每天的课程节数」）。这里**优先读页面里的
    //      var unitCount**（index 的星期/节次换算与每天的节次数都由它决定），页面值与常量不一致时
    //      以页面为准并写进 warnings；页面里读不到才回落常量，并如实说明这个数字是猜的。
    //   ⑤ 开学日：上游把 semesterCalendar 的响应用来解析学期 id 之后就再没用它定开学日，
    //      而是 showPrompt 问用户。这里改成「教务给了学期起始日期就用它（并按手册 §4.3 回退到那一周的
    //      起始日）；拿不到就按学期序号推算」，**推算值一律进 warnings**，不弹窗、不问用户。
    //   ⑥ 教师：args[1] 是字面量就直接用；是 join(...) / actTeacherName 这类**表达式**时，按上游
    //      dlmu 自己的做法从紧邻其前的 var teachers = [...] 块里取 name（同族 CUIT/HPU 也是这样），
    //      取不到才留空（留空，不写「未知」—— 手册 §4.7）。空教室同理。
    //   ⑦ 桥调用一个都没搬：showAlert / showPrompt / showSingleSelection / showToast /
    //      notifyTaskCompletion / saveImportedCourses / savePresetTimeSlots 全部不要。
    //   ⑧ ES6 → ES5：箭头函数、模板串、块级声明、String.replaceAll、扩展运算符全部去掉。
    //   ⑨ 上游把响应 HTML 里的**所有空白**都剥掉了（fetchWithCleanup 里的 replace(/\s/g, "")），
    //      引号字符串里的空格也会一起没；这里不剥，改成让解析器容忍空白（extract.js 交原文）。
    //   ⑩ 课程名不再按「删掉结尾的括号」处理（上游 extractCourseName 会把「高等数学A(一)」的
    //      「(一)」也删掉）。只删同族里有实据的「(10 位数字.2 位数字)」课程代码形态，并在 warnings 里说。
    //   ⑪ 相邻节的排课才合并：上游在单个 TaskActivity 内取 index 的 min/max（中间有间隔也会被算进去）。
    //      这里只并**真正相邻**的段，有间隔就写成两条 block（我们的 blocks 本来就是列表）。
    //   ⑫ 排序全部用确定性的比较（不用 localeCompare —— Rhino 与 V8 排中文的结果未必一致，
    //      而 fixture 是逐数组比对的），每个排序键都补了完整次级键，不依赖排序算法的稳定性。

    var data = JSON.parse(typeof __ncInput === 'undefined' ? '{}' : __ncInput);
    var term = data.term || {};
    var html = typeof data.tableHtml === 'string' ? data.tableHtml : '';

    // 复合键（课名 + 教师）的分隔符取 NUL。用 String.fromCharCode 取，源码里不出现控制字符、
    // 也不出现转义写法 —— 第一批有两个适配器把转义写法落成了真的 NUL 字节，文件被 grep 当二进制看。
    var SEP = String.fromCharCode(0);
    var BS = String.fromCharCode(92);
    var LF = String.fromCharCode(10);
    var CR = String.fromCharCode(13);
    var TAB = String.fromCharCode(9);

    var UPSTREAM_UNIT_COUNT = 10;   // 上游 DLMU/dlmu_01.js 的常量 UNIT_COUNT（注释：每天的课程节数）
    var FALLBACK_TOTAL_WEEKS = 20;  // 上游没有学期总周数，这里用同族的缺省值并如实写进 warnings
    var MAX_WEEK = 30;              // 载荷校验：totalWeeks 与 startWeek/endWeek 都在 1..30
    var MAX_PERIOD = 20;            // 单日节次上限：超过它一定是脏数据
    var MAX_WARNINGS = 20;          // 载荷校验：warnings 最多 20 条
    var MAX_WARNING_CHARS = 200;    // 载荷校验：每条最多 200 字
    var CN_KIND = { '1': '一', '2': '二', '3': '三' };

    // 大连海事大学作息（10 节）：**原样取自上游 DLMU/dlmu_01.js 的 getTimeSlots()**
    //（上游把这张表写给拾光的 savePresetTimeSlots，这里放进载荷的 periodTimes）。
    // 上游没有向教务请求作息 —— 这张表是脚本作者对学校的了解，所以带出去的同时必须写进 warnings
    // 说明它没跟教务核对过。每条都过 timeOf()（HH:mm 且 00:00-23:59）与「结束晚于开始」检查，
    // 不合法的那一节被丢弃并计数（写进载荷的时间越界会让整包被拒，不是跳过一节）。
    var SCHOOL_PERIOD_TIMES = [
        { periodIndex: 1, start: '08:00', end: '08:45' },
        { periodIndex: 2, start: '08:50', end: '09:35' },
        { periodIndex: 3, start: '10:00', end: '10:45' },
        { periodIndex: 4, start: '10:50', end: '11:35' },
        { periodIndex: 5, start: '13:30', end: '14:15' },
        { periodIndex: 6, start: '14:20', end: '15:05' },
        { periodIndex: 7, start: '15:30', end: '16:15' },
        { periodIndex: 8, start: '16:20', end: '17:05' },
        { periodIndex: 9, start: '18:00', end: '18:45' },
        { periodIndex: 10, start: '18:50', end: '19:35' }
    ];

    // 空课内置节次表（:core:model 的 DefaultPeriodTimes，12 节）：课表里用到的节次超出学校作息表时
    // 用它把缺的节次补出来（载荷带了 periodTimes 时应用不会自己补），并写进 warnings。
    var BUILTIN_PERIOD_TIMES = [
        { periodIndex: 1, start: '08:00', end: '08:45' },
        { periodIndex: 2, start: '08:55', end: '09:40' },
        { periodIndex: 3, start: '10:00', end: '10:45' },
        { periodIndex: 4, start: '10:55', end: '11:40' },
        { periodIndex: 5, start: '14:00', end: '14:45' },
        { periodIndex: 6, start: '14:55', end: '15:40' },
        { periodIndex: 7, start: '16:00', end: '16:45' },
        { periodIndex: 8, start: '16:55', end: '17:40' },
        { periodIndex: 9, start: '18:30', end: '19:15' },
        { periodIndex: 10, start: '19:25', end: '20:10' },
        { periodIndex: 11, start: '20:20', end: '21:05' },
        { periodIndex: 12, start: '21:15', end: '22:00' }
    ];

    // 推算开学日的锚点：中国高校第一学期多在 9 月初、第二学期多在 2 月下旬、夏季学期 7 月初。
    // 别直接把锚点当 firstDay：手册 §4.3 要求回退到「第 1 周的第一天」那一周的起始日（缺省周一）。
    var KIND_ANCHOR = {
        '1': { month: 9, day: 1, rule: '第一学期按 9 月 1 日所在的那一周推算' },
        '2': { month: 2, day: 20, rule: '第二学期按 2 月 20 日所在的那一周推算' },
        '3': { month: 7, day: 1, rule: '第三学期（小学期）按 7 月 1 日所在的那一周推算' }
    };

    // ---------- 小工具 ----------
    function text(value) {
        if (value === null || value === undefined) return '';
        return String(value).replace(/\s+/g, ' ').trim();
    }

    function intOf(value) {
        if (value === null || value === undefined || value === '') return null;
        var n = parseInt(value, 10);
        return isNaN(n) ? null : n;
    }

    function pad2(n) {
        return (n < 10 ? '0' : '') + n;
    }

    function isoOf(date) {
        return date.getUTCFullYear() + '-' + pad2(date.getUTCMonth() + 1) + '-' + pad2(date.getUTCDate());
    }

    // 该日期所在周的起始日（缺省周一）。用 UTC 算，避免时区把日期挪一天。
    function weekStartOnOrBefore(year, month, day, firstDayOfWeek) {
        var date = new Date(Date.UTC(year, month - 1, day));
        var offset = (date.getUTCDay() - firstDayOfWeek + 7) % 7;
        return new Date(date.getTime() - offset * 86400000);
    }

    function mondayOnOrBefore(year, month, day) {
        return weekStartOnOrBefore(year, month, day, 1);
    }

    // 任意形态的日期 → ISO 日期。先按开头认（"2026-09-07/2026-09-13" 这种一周区间的写法取前一半），
    // 认不到再在串里找；月日越界返回 null。
    function isoOfAny(value) {
        var s = text(value);
        if (!s) return null;
        var m = /^([0-9]{4})[-/.](\d{1,2})[-/.](\d{1,2})/.exec(s);
        if (!m) m = /([0-9]{4})[-/.](\d{1,2})[-/.](\d{1,2})/.exec(s);
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

    // "08:00" / "08:00:00" → "08:00"；认不出来（含 24:00 这种越界）返回 null。
    // 写进载荷的 periodTimes 必须是 00:00-23:59 的 HH:mm，越界会让整个载荷被拒（不是跳过一节）。
    function timeOf(value) {
        var m = /^([01]?\d|2[0-3]):([0-5]\d)/.exec(text(value));
        if (!m) return null;
        return (m[1].length < 2 ? '0' + m[1] : m[1]) + ':' + m[2];
    }

    function minutesOf(hhmm) {
        return parseInt(hhmm.substring(0, 2), 10) * 60 + parseInt(hhmm.substring(3, 5), 10);
    }

    // 确定性的比较函数：不用 localeCompare（它排中文的结果跟引擎有关，而 fixture 逐数组比对）
    function cmpStr(a, b) {
        if (a === b) return 0;
        return a < b ? -1 : 1;
    }

    function cmpNum(a, b) {
        return a === b ? 0 : (a < b ? -1 : 1);
    }

    // ---------- 学期线索 ----------
    function termKind() {
        var k = text(term.kind);
        if (k === '1' || k === '一') return '1';
        if (k === '2' || k === '二') return '2';
        if (k === '3' || k === '三') return '3';
        var label = text(term.label) + ' ' + text(term.name);
        if (label.indexOf('二') >= 0) return '2';
        if (label.indexOf('三') >= 0) return '3';
        return '1';
    }

    function schoolYearLabel() {
        var s = text(term.schoolYear);
        if (/^[0-9]{4}\s*-\s*[0-9]{2,4}$/.test(s)) return s.replace(/\s/g, '');
        var m = /^([0-9]{4})/.exec(s);
        if (m) return m[1] + '-' + (parseInt(m[1], 10) + 1);
        return '';
    }

    // 学期名：教务给了就用教务的（下拉框里的完整名字优先），拿不到才用「学校名 + 学年学期」。
    // **不拿适配器名当学期名**（手册 §4.7：用户看到「大连海事大学」会以为那是学期）。
    function termName() {
        var label = text(term.label);
        if (label && label.indexOf('学期') >= 0) return label;
        var explicit = text(term.name);
        if (explicit && explicit.indexOf('学期') >= 0) return explicit;
        var year = schoolYearLabel();
        if (year) return '大连海事大学 ' + year + '学年第' + CN_KIND[termKind()] + '学期';
        return '大连海事大学 课表';
    }

    // 开学日：教务给了学期起始日期就用它，并按手册 §4.3 回退到那一周的起始日（缺省周一）；
    // 拿不到就按学期序号推算。两条路都会如实写进 warnings（推算出来的值在库里和真值长得一样，
    // 不说用户就没有任何机会发现 —— 手册 §4.2）。
    function studyStart(todayIso) {
        var given = isoOfAny(term.startDate);
        if (given) {
            return {
                iso: mondayOfIso(given),
                rule: '教务给出的学期起始日期 ' + given,
                estimated: false
            };
        }
        var yearMatch = /^([0-9]{4})/.exec(schoolYearLabel());
        var kind = termKind();
        var anchor = KIND_ANCHOR[kind];
        if (yearMatch) {
            var anchorYear = kind === '1' ? parseInt(yearMatch[1], 10) : parseInt(yearMatch[1], 10) + 1;
            return {
                iso: isoOf(weekStartOnOrBefore(anchorYear, anchor.month, anchor.day, 1)),
                rule: '教务没有给出学期起始日期，' + anchor.rule,
                estimated: true
            };
        }
        // 学年都读不出来（课表页被改过）时退回「最近的一个周一」。这条分支依赖当天日期，
        // 不要写进 fixture 用例 —— 用例会随日期失效
        return {
            iso: mondayOfIso(todayIso || localTodayIso()) || todayIso || localTodayIso(),
            rule: '教务没有给出学期起始日期，学年也读不出来，按最近的一个周一推算',
            estimated: true
        };
    }

    // ---------- 课表页里那份 JS 的字面量与参数 ----------
    // 只做「字符串字面量 → 它的值」和「字面量用 + 拼起来」两种还原，**不构造任何函数、不做算术**。
    // 还原不出来（bind 变量、方法调用、算术表达式）返回 null，由调用方决定回落，绝不猜。
    function unescapeBody(body, quote) {
        var out = body.split(BS + BS).join(BS);
        out = out.split(BS + quote).join(quote);
        out = out.split(BS + 'n').join(LF);
        out = out.split(BS + 'r').join(CR);
        out = out.split(BS + 't').join(TAB);
        return out;
    }

    function joinLiterals(token) {
        var s = String(token === null || token === undefined ? '' : token);
        var out = '';
        var found = false;
        var i = 0;
        while (i < s.length) {
            while (i < s.length && /\s/.test(s.charAt(i))) i++;
            if (i >= s.length) break;
            var q = s.charAt(i);
            if (q !== '"' && q !== "'") return null;
            var j = i + 1;
            var body = '';
            var closed = false;
            while (j < s.length) {
                var ch = s.charAt(j);
                if (ch === BS) {
                    if (j + 1 >= s.length) return null;
                    body += ch + s.charAt(j + 1);
                    j += 2;
                    continue;
                }
                if (ch === q) { closed = true; break; }
                body += ch;
                j++;
            }
            if (!closed) return null;
            out += unescapeBody(body, q);
            found = true;
            i = j + 1;
            while (i < s.length && /\s/.test(s.charAt(i))) i++;
            if (i >= s.length) break;
            if (s.charAt(i) !== '+') return null;
            i++;
        }
        return found ? out : null;
    }

    function argValue(token) {
        var value = joinLiterals(token);
        if (value !== null) return value;
        var s = text(token);
        if (s === 'null' || s === 'undefined') return '';
        return null;
    }

    // 参数列表：引号与括号都要配对（课程名里的「(一)」和引号里的逗号都不能提前收尾）
    function splitArgs(raw) {
        var out = [];
        var cur = '';
        var depth = 0;
        var quote = '';
        var escaped = false;
        var i;
        for (i = 0; i < raw.length; i++) {
            var ch = raw.charAt(i);
            if (escaped) { cur += ch; escaped = false; continue; }
            if (ch === BS) { cur += ch; escaped = true; continue; }
            if (quote) { cur += ch; if (ch === quote) quote = ''; continue; }
            if (ch === '"' || ch === "'") { cur += ch; quote = ch; continue; }
            if (ch === '(' || ch === '[' || ch === '{') depth++;
            else if (ch === ')' || ch === ']' || ch === '}') depth--;
            if (ch === ',' && depth === 0) { out.push(cur); cur = ''; continue; }
            cur += ch;
        }
        if (cur !== '') out.push(cur);
        return out;
    }

    // new TaskActivity( 之后到配对的 ")" 为止 —— 同样地，引号里的 ")" 不算
    function argsTextAt(source, openIndex) {
        var depth = 0;
        var quote = '';
        var escaped = false;
        var i;
        for (i = openIndex; i < source.length; i++) {
            var ch = source.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (ch === BS) { escaped = true; continue; }
            if (quote) { if (ch === quote) quote = ''; continue; }
            if (ch === '"' || ch === "'") { quote = ch; continue; }
            if (ch === '(' || ch === '[' || ch === '{') depth++;
            else if (ch === ')' || ch === ']' || ch === '}') {
                depth--;
                if (depth === 0) return { text: source.substring(openIndex + 1, i), end: i };
            }
        }
        return null;
    }

    // ---------- index 表达式（本件的核心改动：不求值、不用函数构造器） ----------
    // 树维 eams 的 index 只有两种形态（上游 dlmu 自己那条正则也只认这两种）：
    //   ① index = 5*unitCount+2 —— 5 是星期序（从 0 起）、+2 是节次（从 0 起）
    //   ② index = 62 --------- 教务已经算好的线性下标
    // 线性下标 → 星期 = floor(index / unitCount) + 1、节次 = index % unitCount + 1
    //（上游 parseIndices() 的注释表格：0-9 = 周一第 1-10 节、10-19 = 周二第 1-10 节 …）。
    // 认不出来一律返回 null：调用方丢弃那条排课并计数进 warnings。**绝不**把这个字符串
    // 交给函数构造器当代码执行 —— 它是从网络取回的 HTML 里抓出来的。
    function readIndex(expr, unitCount) {
        var s = text(expr);
        if (!s) return null;
        if (/^[0-9]+$/.test(s)) return parseInt(s, 10);
        // 教务为「取当前周次」在页面里生成的形态：index = 星期(0 起) * unitCount + 节次(0 起)。
        // unitCount 有时已被页面替换成数字，所以两种都认；遇到「结果算不出 / 有负偏移」的
        // 其它写法（如 3*unitCount-2）一律当认不出返回 null，由调用方跳过并计数 —— 绝不猜。
        var m = /^([0-9]+)\s*\*\s*(?:unitCount|([0-9]+))\s*(?:\+\s*([0-9]+))?$/.exec(s);
        if (!m) return null;
        var factor = parseInt(m[1], 10);
        var unit = m[2] === undefined ? unitCount : parseInt(m[2], 10);
        var plus = m[3] === undefined ? 0 : parseInt(m[3], 10);
        if (!(unit >= 1)) return null;
        var value = factor * unit + plus;
        return value >= 0 ? value : null;
    }

    // ---------- 周次位图 ----------
    // 本批统一口径（同族 5 件写下的约定）：位图下标 i 就是第 i 周，下标 0 是占位符。
    // 上游 dlmu 的循环下标从 0 起、见到字面字符 1 就把下标 push 进周次数组（**没有跳过 0 位**），
    // 位图第 0 位为 1 时会产出「第 0 周」（载荷校验会拒，而且不声不响）。
    var bitmapZero = false;     // 第 0 位出现过 1
    var bitmapOverflow = 0;     // 超过 MAX_WEEK 的位个数

    function weeksOfBitmap(bitmap) {
        var out = [];
        var i;
        for (i = 0; i < bitmap.length; i++) {
            if (bitmap.charAt(i) !== '1') continue;
            if (i === 0) { bitmapZero = true; continue; }
            if (i > MAX_WEEK) { bitmapOverflow++; continue; }
            out.push(i);
        }
        return out;
    }

    // ---------- 教师：args[1] 是表达式时，从紧邻其前的 var teachers 块里取名字 ----------
    // 上游 dlmu 就是这么做的（extractTeacherNames 从 teachers 数组里读 name 字段），
    // 同族的 CUIT / HPU 也是「找 activity 之前最近的一个 teachers 块」。
    function teachersBefore(source, beforeIndex) {
        var re = /var\s+teachers\s*=\s*\[/g;
        var last = null;
        var m;
        while ((m = re.exec(source)) !== null) {
            if (m.index >= beforeIndex) break;
            last = m;
        }
        if (!last) return [];
        var open = source.indexOf('[', last.index);
        if (open < 0) return [];
        var close = source.indexOf('];', open);
        var block = source.substring(open + 1, close < 0 ? source.length : close);
        var names = [];
        var seen = {};
        var nameRe = /name\s*:\s*(?:"([^"]*)"|'([^']*)')/g;
        var nm;
        while ((nm = nameRe.exec(block)) !== null) {
            var value = text(nm[1] !== undefined ? nm[1] : nm[2]);
            if (!value || seen[value]) continue;
            seen[value] = true;
            names.push(value);
        }
        return names;
    }

    // ---------- 课程名 ----------
    var codeStripped = 0;
    function courseNameOf(raw) {
        var name = text(raw);
        if (!name) return '';
        // 同族里有实据的课程代码形态（CUIT 的 cleanCourseName 删的就是它）。上游 dlmu 的
        // extractCourseName 会删掉**任意**结尾括号，那会把「高等数学A(一)」的「(一)」也删掉，
        // 也可能吃掉课名本身带括号的部分，所以这里只删这一种并写进 warnings。
        if (/\([0-9]{10}\.[0-9]{2}\)$/.test(name)) {
            name = text(name.replace(/\([0-9]{10}\.[0-9]{2}\)$/, ''));
            codeStripped++;
        }
        return name;
    }

    // ---------- 主循环：把课表 HTML 里的 TaskActivity 块读成排课行 ----------
    var rows = [];
    var actMissing = 0;      // 参数不足 7 个的 activity
    var nameMissing = 0;     // 没有课程名的 activity
    var bitmapEmpty = 0;     // 位图里没有任何有效周次的 activity
    var indexBad = 0;        // index 表达式认不出来的条数
    var indexBadSample = '';
    var indexMissing = 0;    // 整段里一个「index =」都没有的 activity
    var indexOutOfRange = 0; // index 算出来落在 8 天以外 / 节次越界
    var teacherExpr = 0;     // 教师是表达式且 teachers 块里也找不到名字
    var dupRows = 0;         // 完全重复的排课行
    var seenRows = {};

    var actRe = /activity\s*=\s*new\s+TaskActivity\s*\(/g;
    var marks = [];
    var mark;
    while ((mark = actRe.exec(html)) !== null) {
        marks.push({ at: mark.index, open: mark.index + mark[0].length - 1 });
    }

    // 节次数：优先页面里的 var unitCount（index 的星期/节次换算与每天的节次数都由它决定，
    // 错了整学期的课都会错位），读不到才回落上游常量，并如实说明这个数字是猜的。
    var pageUnitCount = intOf(data.unitCountPage);
    var unitCount = UPSTREAM_UNIT_COUNT;
    var unitCountFromPage = false;
    if (pageUnitCount !== null && pageUnitCount >= 1 && pageUnitCount <= MAX_PERIOD) {
        unitCount = pageUnitCount;
        unitCountFromPage = true;
    }

    var ai;
    for (ai = 0; ai < marks.length; ai++) {
        var current = marks[ai];
        var argsText = argsTextAt(html, current.open);
        if (!argsText) { actMissing++; continue; }
        var args = splitArgs(argsText.text);
        if (args.length < 7) { actMissing++; continue; }

        var nextAt = ai + 1 < marks.length ? marks[ai + 1].at : html.length;
        var following = html.substring(argsText.end + 1, nextAt);

        var name = courseNameOf(argValue(args[3]));
        if (!name) { nameMissing++; continue; }

        var teacher = argValue(args[1]);
        if (teacher === null) {
            var names = teachersBefore(html, current.at);
            teacher = names.length ? names.join(',') : '';
            if (!teacher) teacherExpr++;
        }
        teacher = text(teacher);

        var room = argValue(args[5]);
        room = room === null ? '' : text(room);

        var bitmap = argValue(args[6]);
        bitmap = bitmap === null ? '' : text(bitmap);
        var weeks = weeksOfBitmap(bitmap);
        if (!weeks.length) { bitmapEmpty++; continue; }

        // 这一段里所有「index = … ;」的内容。**不求值**：交给 readIndex 按两种形态解析。
        var indexRe = /index\s*=\s*([^;]*?)\s*;/g;
        var indexMatch;
        var sawIndex = false;
        var positions = [];
        while ((indexMatch = indexRe.exec(following)) !== null) {
            sawIndex = true;
            var expr = text(indexMatch[1]);
            var linear = readIndex(expr, unitCount);
            if (linear === null) {
                indexBad++;
                if (!indexBadSample) indexBadSample = expr;
                continue;
            }
            var day = Math.floor(linear / unitCount) + 1;
            var period = (linear % unitCount) + 1;
            if (day < 1 || day > 7 || period < 1 || period > MAX_PERIOD) { indexOutOfRange++; continue; }
            positions.push({ day: day, section: period });
        }
        if (!sawIndex) { indexMissing++; continue; }
        if (!positions.length) continue;   // 一条都没算出来：上面已经计数（indexBad / indexOutOfRange）

        var pi;
        for (pi = 0; pi < positions.length; pi++) {
            var rowKey = name + SEP + teacher + SEP + room + SEP + positions[pi].day + SEP +
                positions[pi].section + SEP + weeks.join(',');
            if (seenRows[rowKey]) { dupRows++; continue; }
            seenRows[rowKey] = true;
            rows.push({
                name: name,
                teacher: teacher,
                position: room,
                day: positions[pi].day,
                startSection: positions[pi].section,
                endSection: positions[pi].section,
                weeks: weeks.slice(0)
            });
        }
    }

    if (!rows.length) {
        // 两种失败要分清楚：教务说「这个学期没课」和「给了课表但我们一条都没读懂」，
        // 后者八成是页面结构变了，报「可能还没排课」会把用户和我们都带偏。
        if (marks.length > 0) {
            throw new Error(
                '课表里有 ' + marks.length + ' 段 TaskActivity，但没有一条能解析成课程（缺课名 ' +
                nameMissing + ' 段、缺周次 ' + bitmapEmpty + ' 段、缺 index ' + indexMissing +
                ' 段、index 认不出 ' + indexBad + ' 条、index 越界 ' + indexOutOfRange +
                ' 条、参数不足 ' + actMissing + ' 段）：多半是教务系统改了课表页面的结构，' +
                '请把这条消息反馈给我们'
            );
        }
        throw new Error(
            '这个学期的课表是空的：可能还没排课（假期里常见），也可能登录状态已失效。' +
            '请重新登录、确认页面上能看到课表后再点「提取课表」'
        );
    }

    // ---------- 合并相邻节次 ----------
    // 上游在单个 TaskActivity 内取 index 的 min/max（中间有间隔也会被算进去）。这里只并
    // **真正相邻**的段：课名/教师/教室/星期/周次全同、且前一段的末节 + 1 等于后一段的首节。
    // 有间隔的保留成两条 block（我们的 blocks 本来就是列表，语义等价而且更准）。
    function mergeContinuous(list) {
        var copy = [];
        var i;
        for (i = 0; i < list.length; i++) {
            copy.push({
                name: list[i].name,
                teacher: list[i].teacher,
                position: list[i].position,
                day: list[i].day,
                startSection: list[i].startSection,
                endSection: list[i].endSection,
                weeks: list[i].weeks.slice(0).sort(function (a, b) { return a - b; })
            });
        }
        copy.sort(function (a, b) {
            return cmpStr(a.name, b.name) || cmpStr(a.teacher, b.teacher) ||
                cmpStr(a.position, b.position) || cmpNum(a.day, b.day) ||
                cmpNum(a.startSection, b.startSection) || cmpNum(a.endSection, b.endSection) ||
                cmpStr(a.weeks.join(','), b.weeks.join(','));
        });
        var merged = [];
        for (i = 0; i < copy.length; i++) {
            var cur = copy[i];
            var prev = merged.length ? merged[merged.length - 1] : null;
            if (prev && prev.name === cur.name && prev.teacher === cur.teacher &&
                prev.position === cur.position && prev.day === cur.day &&
                prev.weeks.join(',') === cur.weeks.join(',') &&
                prev.endSection + 1 === cur.startSection) {
                prev.endSection = cur.endSection;
                continue;
            }
            merged.push(cur);
        }
        return merged;
    }

    // 周次集合 → 极大段：步长 1 视作每周，步长 2 视作单周 / 双周（手册 §4.1）。
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

    var mergedRows = mergeContinuous(rows);

    // 课程顺序按**首次出现的排课行**（不依赖合并后的排序结果，方便人工核对）
    var order = [];
    var byCourse = {};
    var i2;
    for (i2 = 0; i2 < rows.length; i2++) {
        var courseKey = rows[i2].name + SEP + rows[i2].teacher;
        if (byCourse[courseKey]) continue;
        byCourse[courseKey] = {
            name: rows[i2].name,
            teacher: rows[i2].teacher ? rows[i2].teacher : null,
            note: null,
            blocks: [],
            seen: {}
        };
        order.push(courseKey);
    }

    var maxWeek = 0;
    var maxPeriod = 0;
    for (i2 = 0; i2 < mergedRows.length; i2++) {
        var item = mergedRows[i2];
        var course = byCourse[item.name + SEP + item.teacher];
        if (!course) continue;
        var runs = runsOf(item.weeks);
        var k;
        for (k = 0; k < runs.length; k++) {
            var run = runs[k];
            var blockKey = item.day + '|' + item.startSection + '|' + item.endSection + '|' +
                run.start + '|' + run.end + '|' + run.weekType + '|' + item.position;
            if (course.seen[blockKey]) continue;
            course.seen[blockKey] = true;
            if (run.end > maxWeek) maxWeek = run.end;
            if (item.endSection > maxPeriod) maxPeriod = item.endSection;
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
    for (i2 = 0; i2 < order.length; i2++) {
        var built = byCourse[order[i2]];
        built.blocks.sort(function (a, b) {
            return cmpNum(a.dayOfWeek, b.dayOfWeek) || cmpNum(a.startPeriod, b.startPeriod) ||
                cmpNum(a.endPeriod, b.endPeriod) || cmpNum(a.startWeek, b.startWeek) ||
                cmpNum(a.endWeek, b.endWeek) || cmpStr(a.weekType, b.weekType) ||
                cmpStr(a.location || '', b.location || '');
        });
        courses.push({ name: built.name, teacher: built.teacher, note: built.note, blocks: built.blocks });
    }

    // ---------- 学期名 / 开学日 / 总周数 ----------
    var name0 = termName();
    var todayIso = isoOfAny(data.today) || localTodayIso();
    var start = studyStart(todayIso);

    var weekCount = intOf(term.weekCount);
    var totalWeeks;
    var weeksSource;
    var weeksClamped = false;
    if (weekCount !== null && weekCount > MAX_WEEK) {
        totalWeeks = MAX_WEEK;
        weeksSource = '教务给出的学期周数（' + weekCount + ' 周）';
        weeksClamped = true;
    } else if (weekCount !== null && weekCount >= 1) {
        totalWeeks = weekCount;
        weeksSource = '教务给出的学期周数（' + weekCount + ' 周）';
    } else {
        totalWeeks = FALLBACK_TOTAL_WEEKS;
        weeksSource = '适配器内置的 ' + FALLBACK_TOTAL_WEEKS +
            ' 周（教务没有给出学期周数，这个数字取自上游同族脚本的缺省值）';
    }
    var raisedBySchedule = false;
    if (maxWeek > totalWeeks) { totalWeeks = maxWeek; raisedBySchedule = true; }
    if (totalWeeks > MAX_WEEK) totalWeeks = MAX_WEEK;

    // ---------- 作息时间 ----------
    // 上游没有向教务请求作息，用的是脚本里那张 10 节预设表 —— 先逐条验时间合法性
    // （写进 periodTimes 的时间必须是 00:00-23:59 的 HH:mm，越界会让整个载荷被拒），
    // 再用空课内建节次表把课表里用到、作息表没覆盖的节次补出来。
    var periodTimes = [];
    var badSlots = 0;
    for (i2 = 0; i2 < SCHOOL_PERIOD_TIMES.length; i2++) {
        var slot = SCHOOL_PERIOD_TIMES[i2];
        var slotStart = timeOf(slot.start);
        var slotEnd = timeOf(slot.end);
        if (!slotStart || !slotEnd || minutesOf(slotStart) >= minutesOf(slotEnd)) { badSlots++; continue; }
        periodTimes.push({ periodIndex: slot.periodIndex, start: slotStart, end: slotEnd });
    }
    var tableLength = periodTimes.length;
    var extendedTo = 0;
    var uncoveredFrom = 0;
    var p;
    for (p = tableLength + 1; p <= maxPeriod; p++) {
        var fallbackSlot = BUILTIN_PERIOD_TIMES[p - 1];
        if (fallbackSlot) {
            periodTimes.push({ periodIndex: p, start: fallbackSlot.start, end: fallbackSlot.end });
            extendedTo = p;
        } else if (!uncoveredFrom) {
            uncoveredFrom = p;
        }
    }

    // ---------- warnings ----------
    // 顺序固定：算出来的、猜出来的、丢掉的都要说清楚。上限 20 条 / 每条 200 字（超了整个载荷会被拒），
    // 超出的在最后一条里如实说明，不静默截断。
    var allWarnings = [];

    function warn(message) {
        var line = String(message);
        if (line.length > MAX_WARNING_CHARS) line = line.substring(0, MAX_WARNING_CHARS - 1) + '…';
        allWarnings.push(line);
    }

    warn(
        '只导入了教务系统当前选中的学期（' + name0 +
        '）；要导入别的学期，请在教务页面里切到那个学期再点「提取课表」'
    );

    if (!start.estimated) {
        warn('开学日期取自教务系统给出的学期起始日期：第 1 周从 ' + start.iso + ' 开始，请在学期管理里核对');
    } else {
        warn(
            '教务系统没有给出可用的学期起始日期，第 1 周按「' + start.rule + '」推算为 ' + start.iso +
            '，请在学期管理里核对成学校实际开学日'
        );
    }

    if (raisedBySchedule) {
        warn(
            '学期总周数用的是' + weeksSource + '，但课表里有第 ' + maxWeek + ' 周的课，已按 ' + totalWeeks +
            ' 周导入（否则那几周的课放不下），如与实际不符可在学期管理里改'
        );
    } else {
        warn(
            '学期总周数用的是' + weeksSource +
            (weeksClamped ? '，已按载荷上限 ' + MAX_WEEK + ' 周截断' : '') +
            '，如与实际不符可在学期管理里改'
        );
    }

    if (unitCountFromPage) {
        if (unitCount !== UPSTREAM_UNIT_COUNT) {
            warn(
                '课表页里的 var unitCount = ' + unitCount + '，与上游脚本内置的常量 ' + UPSTREAM_UNIT_COUNT +
                ' 不一致：本适配器以页面值为准（每天的节次数与每门课的星期/节次换算都由它决定）。' +
                '真机核对时若发现整学期的课都错位，先查这一处'
            );
        }
    } else {
        warn(
            '课表页里没有读到可用的 var unitCount，每天的节次数用的是上游脚本内置的常量 ' +
            UPSTREAM_UNIT_COUNT + '（上游注释：每天的课程节数）—— 这个数字是猜的，' +
            '星期与节次换算都挂在它上面，请对照教务处公布的作息核对'
        );
    }

    warn(
        '作息时间用的是适配器内置的大连海事大学 ' + tableLength + ' 节作息表（第 1 节 ' +
        SCHOOL_PERIOD_TIMES[0].start + '-' + SCHOOL_PERIOD_TIMES[0].end +
        '），出自上游脚本、没有向教务核对过，如与学校实际作息不符请在学期管理里改'
    );

    if (extendedTo || uncoveredFrom) {
        var notes = [];
        if (extendedTo) {
            notes.push(
                extendedTo > tableLength + 1
                    ? ('第 ' + (tableLength + 1) + '-' + extendedTo + ' 节按空课内建节次表补了时间')
                    : ('第 ' + extendedTo + ' 节按空课内建节次表补了时间')
            );
        }
        if (uncoveredFrom) notes.push('第 ' + uncoveredFrom + ' 节及之后没有可用的作息时间');
        warn(
            '课表里用到第 ' + maxPeriod + ' 节，而内置作息表只到第 ' + tableLength + ' 节：' +
            notes.join('；') + '，请核对'
        );
    }

    if (bitmapZero) {
        warn(
            '周次位图的第 0 位为 1，与同族约定的「第 0 位是占位符」不符，已按忽略处理' +
            '（否则会产出不存在的「第 0 周」），请在导入预览里核对周次'
        );
    }
    if (bitmapOverflow > 0) {
        warn('有 ' + bitmapOverflow + ' 个周次位超过 ' + MAX_WEEK + ' 周，已丢弃（教务给出的周次位图不正常）');
    }
    if (bitmapEmpty > 0) {
        warn(
            '有 ' + bitmapEmpty + ' 段课表的周次位图里没有任何有效周次，这些课已被跳过：' +
            '教务数据不完整时会出现，如发现少课请反馈'
        );
    }
    if (indexBad > 0) {
        warn(
            '有 ' + indexBad + ' 条排课的 index 表达式认不出来' +
            (indexBadSample ? '（例如「' + indexBadSample + '」）' : '') +
            '，这些排课已被跳过 —— 本适配器不执行课表页里的表达式，只按正则解析 ' +
            'index = <数字> 与 index = <数字>*unitCount+<数字>（也接受 unitCount 已被替换成数字的写法），请反馈'
        );
    }
    if (indexOutOfRange > 0) {
        warn('有 ' + indexOutOfRange + ' 条排课的 index 算出来落在 8 天以外或节次越界，已跳过，请反馈');
    }
    if (indexMissing > 0) {
        warn(
            '有 ' + indexMissing + ' 段课表里一条 index 都没有，这些课已被跳过：' +
            '多半是教务改了课表页面的结构，请反馈'
        );
    }
    if (nameMissing > 0) {
        warn('有 ' + nameMissing + ' 段课表没有课程名，已跳过（教务数据不完整时会出现）');
    }
    if (actMissing > 0) {
        warn('有 ' + actMissing + ' 段课表的参数不足 7 个，已跳过（教务页面结构可能变了），请反馈');
    }
    if (teacherExpr > 0) {
        warn(
            '有 ' + teacherExpr + ' 门课的教师写成了课表页里的表达式（例如 actTeachers 这类变量），' +
            '前后也找不到可用的教师名单，教师已留空 —— 本适配器不执行页面脚本'
        );
    }
    if (dupRows > 0) {
        warn('有 ' + dupRows + ' 条完全重复的排课（课名/教师/教室/星期/节次/周次全同）已去重');
    }
    if (codeStripped > 0) {
        warn('有 ' + codeStripped + ' 门课的课名末尾是课程代码形态（10 位数字.2 位小数），已去掉它，请核对课名');
    }
    if (badSlots > 0) {
        warn('适配器内置作息表里有 ' + badSlots + ' 节的时间不合法，已丢弃这些节次，请反馈');
    }

    var warnings = allWarnings;
    if (allWarnings.length > MAX_WARNINGS) {
        warnings = allWarnings.slice(0, MAX_WARNINGS - 1);
        warnings.push(
            '另有 ' + (allWarnings.length - MAX_WARNINGS + 1) +
            ' 条说明因为超出上限没有显示，请把这份课表反馈给我们'
        );
    }

    return JSON.stringify({
        specVersion: 1,
        kind: 'schedule',
        ocrAssisted: false,
        warnings: warnings,
        terms: [
            {
                name: name0,
                firstDay: start.iso,
                totalWeeks: totalWeeks,
                periodTimes: periodTimes,
                courses: courses
            }
        ]
    });
})()
