(function () {
    // 西安工业大学教务适配器（树维 EAMS 平台）—— 第二步：extract.js 交出来的原始数据 → 空课课表载荷。
    //
    // 移植自 shiguang_warehouse 的 XATU/myschool.js
    //   https://github.com/XingHeYuZhuan/shiguang_warehouse  （MIT，上游 maintainer 晨熯）
    //   上游快照 e62554a4034386b893bcd6813c7b2b64f8c730a3（2026-09-12）
    //
    // 上游把取数与解析揉在同一个自执行脚本里；这里只做纯转换（不碰页面、不发请求，CI 里用 Rhino 实跑），
    // 取数全部在 extract.js。上游解析那段的核心三件（powerSplit / mergeContinuousLessons /
    // parseTaskActivities）语义原样保留，逐条改动见下。
    //
    // ⚠️ 本件与同族的 tjau / hfnu 是**逐字克隆**（上游 xatu 的文件头自己写着「基于天津农学院适配脚本」；
    //    diff 过三份：解析逻辑一字不差，只有主机、作息表、提示语不同）。所以这里**没有**照抄邻件的
    //    期望值 —— fixture 全部按本校自己的主机与作息表另造，见 AUDIT.md §3。
    //
    // 移植改动：
    //   ① ES6 → ES5：去掉模板串、箭头函数、展开运算符、块级声明关键字、Set / Array.from 这类
    //      非 ES5 结构（上游的周次矩阵用了 50 个 Set）。合并语义不变，实现换成等价的数组结构。
    //   ② 周次位图按**本批统一口径**读：bitmap[i] === '1' 且 i >= 1 → 第 i 周。
    //      上游 XATU/myschool.js 写的是 「for (j = 0; j < len; j++) if (bitmap[j] === '1') weeks.push(j)」，
    //      **0 位为 1 时会产出「第 0 周」**（载荷校验也会拒）。本件 0 位不产出周次，改为写一条
    //      warnings；同族 uestc / hpu / hunnu / zua / zzvca 五件共同声明「下标即周次、0 位是占位符」。
    //      依据与反例见 AUDIT.md §5 第 1 条。
    //   ③ 课程名保真：上游 「(args[3] || "未知课程").split('(')[0]」 会把「高等数学A(一)」砍成
    //      「高等数学A」(丢信息)。同族另有五件只去**尾部**的课程代码括号。这里改成：先取最后一个
    //      括号组，只有当它是**明显的课程代码**（纯数字 / 数字区间 / 纯大写字母 + 数字 /「课程代码」
    //      字样，「数据结构(2024.01)」这种）时才去掉，否则课程名原样保留。见 AUDIT.md §7。
    //   ④ 教室原样保留：上游 「.replace(/\(.*?\)/g, "")」 会去掉教室里的**全部**括号
    //      （「教3-101(东)」→「教3-101」）。手册 §4.7 说教室可以原样带，这里只做空白归一。
    //   ⑤ 教师 / 教室拿不到就留空（null）：上游写「未知教师」/「未知地点」，会被当成真名、真地点显示。
    //   ⑥ 教师取值的加固：args[1] 有的部署写成 「teachers.join(",")」 这类**表达式**，
    //      上游直接把它当教师名（字符串「xxx.join(...)」）。这里先剥表达式，再取同一块里
    //      teachers 数组的名字（上游只取第一个 actTeachers 的 name，一个块里多位老师会少人）。
    //   ⑦ index 的两种写法都认：「index = 5*unitCount+2」 与**已算好的裸数字** 「index = 62」
    //      （同族 hpu 就两种都认）。禁止 eval / new Function（手册 §5 第 6 条）—— 本件只用正则。
    //   ⑧ unitCount 读不到时，节次数写成缺省 14，并且**同时**写一条 warnings（上游静默用 14）。
    //   ⑨ 开学日：优先用 semesterCalendar 里当前学期的起止日期（回退到那一周的周一，手册 §4.3）；
    //      拿不到就按学期序号推算，推算值一定出现在 warnings 里。上游是 showSingleSelection 弹窗
    //      选学期，本件不问用户（手册 §3 第 1 步）。
    //   ⑩ 作息表来自上游 XATU/myschool.js 内置的那张 12 节表（不是页面读取），写进载荷的同时
    //      在 warnings 里说明「非教务读取，真机核对时请对照教务处公布的作息」。
    //   ⑪ 学期列表的响应（semesterRaw）**不执行**：上游用 Function("return (" + raw + ")") 求值，
    //      命中手册 §5 第 6 条。这里用「花括号配平切块 + 逐字段正则」只把字段读出来。
    //   ⑫ 学期名用教务给的年号 + 第几学期（「2026-2027学年第一学期」），拿不到用「西安工业大学 +
    //      学年学期」；别用适配器名当学期名（手册 §4.7）。
    //   ⑬ 排序确定性：上游用 localeCompare 排中文课名，Rhino 与 V8 的结果未必一致，而 fixture
    //      是逐数组比对的。这里改成按码位比较，课程顺序按首次出现的活动排（载荷语义与顺序无关）。
    //   ⑭ 周次集合先按极大段切（步长 1 → ALL、步长 2 → 单/双周），一个活动可以切出多个 block
    //      —— 手册 §4.1，与同族其它移植件同一口径。

    var data = JSON.parse(typeof __ncInput === 'undefined' ? '{}' : __ncInput);
    var courseHtml = rawText(data.courseTableHtml);
    var todayIso = text(data.today);
    var currentSemesterId = text(data.currentSemesterId);

    // 复合键（课名 + 教师 + 教室 + 星期）的分隔符取 NUL。用 String.fromCharCode 取，
    // 源码里既不出现控制字符、也不出现 unicode 转义序列（第一批有两个适配器把转义序列
    // 落成了真的 NUL 字节，文件被 grep 当二进制看）。
    var SEP = String.fromCharCode(0);

    var MAX_WEEK = 30;            // 载荷校验：startWeek / endWeek / totalWeeks ∈ 1..30
    var MAX_DAY = 7;              // 载荷校验：dayOfWeek ∈ 1..7
    var MAX_PERIOD = 30;          // 单日节次上限，超过它一定是脏数据
    var MAX_WARNING_CHARS = 200;  // 载荷校验：每条 warnings ≤ 200 字
    var MAX_WARNINGS = 20;        // 载荷校验：warnings ≤ 20 条
    var DEFAULT_UNIT_COUNT = 14;  // 上游 XATU/myschool.js 的缺省值
    var FALLBACK_TOTAL_WEEKS = 20;

    // 学校作息（西安工业大学）：取自上游 XATU/myschool.js 里那张预设节次表（savePresetTimeSlots），
    // 原样搬过来。上游**没有向教务请求作息**，这张表是脚本作者对学校的了解 —— 所以它是一个
    // 适配器自带的值，写进载荷的同时必须写进 warnings 说明它没跟教务核对过。
    var SCHOOL_PERIOD_TIMES = [
        { periodIndex: 1, start: '08:20', end: '09:05' },
        { periodIndex: 2, start: '09:15', end: '10:00' },
        { periodIndex: 3, start: '10:20', end: '11:05' },
        { periodIndex: 4, start: '11:15', end: '12:00' },
        { periodIndex: 5, start: '14:00', end: '14:45' },
        { periodIndex: 6, start: '14:55', end: '15:40' },
        { periodIndex: 7, start: '16:00', end: '16:45' },
        { periodIndex: 8, start: '16:55', end: '17:40' },
        { periodIndex: 9, start: '18:10', end: '18:55' },
        { periodIndex: 10, start: '19:05', end: '19:50' },
        { periodIndex: 11, start: '20:00', end: '20:45' },
        { periodIndex: 12, start: '20:55', end: '21:40' }
    ];

    var warnings = [];

    function warn(message) {
        var line = text(message);
        if (!line) return;
        if (line.length > MAX_WARNING_CHARS) line = line.substring(0, MAX_WARNING_CHARS - 1) + '…';
        if (warnings.indexOf(line) >= 0) return;
        if (warnings.length >= MAX_WARNINGS - 1) {
            var tail = '另有说明因为超出上限没有显示，请把这份课表反馈给适配器作者';
            if (warnings.indexOf(tail) < 0) warnings.push(tail);
            return;
        }
        warnings.push(line);
    }

    // ---------- 小工具 ----------
    function text(value) {
        if (value === null || value === undefined) return '';
        return String(value).replace(/\s+/g, ' ').trim();
    }

    function rawText(value) {
        if (value === null || value === undefined) return '';
        return String(value);
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

    // 任意形态的日期 → ISO 日期：先按开头认（"2026-09-07/2026-09-13" 这种一周区间取前一半），
    // 认不到再在串里找（"2026-9-7 至 2026-9-13" 这类）。
    function isoOfAny(value) {
        var s = text(value);
        var m = /^(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})/.exec(s);
        if (!m) m = /(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})/.exec(s);
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

    function daysBetween(fromIso, toIso) {
        if (!fromIso || !toIso) return null;
        var from = Date.UTC(
            parseInt(fromIso.substring(0, 4), 10),
            parseInt(fromIso.substring(5, 7), 10) - 1,
            parseInt(fromIso.substring(8, 10), 10)
        );
        var to = Date.UTC(
            parseInt(toIso.substring(0, 4), 10),
            parseInt(toIso.substring(5, 7), 10) - 1,
            parseInt(toIso.substring(8, 10), 10)
        );
        if (isNaN(from) || isNaN(to)) return null;
        return Math.round((to - from) / 86400000);
    }

    // "08:20" / "08:20:00" → "08:20"；认不出来（含 24:00 这种越界）返回 null。
    // 写进载荷的 periodTimes 必须是 00:00-23:59 的 HH:mm，越界会让整个载荷被拒。
    function timeOf(value) {
        var m = /^([01]?\d|2[0-3]):([0-5]\d)/.exec(text(value));
        if (!m) return null;
        return (m[1].length < 2 ? '0' + m[1] : m[1]) + ':' + m[2];
    }

    function minutesOf(hhmm) {
        return parseInt(hhmm.substring(0, 2), 10) * 60 + parseInt(hhmm.substring(3, 5), 10);
    }

    function hhmmOf(minutes) {
        return pad2(Math.floor(minutes / 60)) + ':' + pad2(minutes % 60);
    }

    // 确定性的比较函数（不用 localeCompare：它排中文的结果与引擎有关，而 fixture 逐数组比对）
    function cmpStr(a, b) {
        if (a === b) return 0;
        return a < b ? -1 : 1;
    }

    function cmpNum(a, b) {
        return a === b ? 0 : (a < b ? -1 : 1);
    }

    // ---------- TaskActivity 参数切分（上游 powerSplit，语义原样保留） ----------
    // 引号内与括号 / 方括号 / 花括号里的逗号不是分隔符。上游逐字符扫描，这里原样搬，
    // 只把块级声明换成 var、去掉模板串。
    function cleanArg(s) {
        var value = rawText(s).replace(/^\s+|\s+$/g, '');
        if (value === 'null') return null;
        return value.replace(/^["']|["']$/g, '');
    }

    function powerSplit(paramsRaw) {
        var source = rawText(paramsRaw);
        var args = [];
        var current = '';
        var depth = 0;
        var inQuote = false;
        var quoteChar = '';
        var i;
        var char;
        for (i = 0; i < source.length; i++) {
            char = source.charAt(i);
            if ((char === '"' || char === "'") && (i === 0 || source.charAt(i - 1) !== '\\')) {
                if (!inQuote) { inQuote = true; quoteChar = char; }
                else if (char === quoteChar) { inQuote = false; }
            }
            if (!inQuote) {
                if (char === '(' || char === '[' || char === '{') depth++;
                if (char === ')' || char === ']' || char === '}') depth--;
            }
            if (char === ',' && depth === 0 && !inQuote) {
                args.push(cleanArg(current));
                current = '';
            } else {
                current += char;
            }
        }
        args.push(cleanArg(current));
        return args;
    }

    // 参数是不是一段代码（而不是字面量）：「teachers.join(",")」 这类。上游直接把它当教师名
    // 交给用户（课表里会出现「teachers.join(",")」），这里识别出来并回到块里的 teachers 数组取值。
    function looksLikeExpression(value) {
        var s = text(value);
        if (!s) return false;
        return /[(){};]/.test(s) || /\.join\s*\(/.test(s);
    }

    // 同一块里 teachers / actTeachers 数组里的姓名，按出现顺序拼起来。
    // 上游只取第一个 actTeachers 的 name，一个块里挂多位老师时会少人，这里都取。
    function teacherNamesOf(block) {
        var source = rawText(block);
        var re = /name\s*:\s*(?:"([^"]*)"|'([^']*)')/g;
        var names = [];
        var m;
        while ((m = re.exec(source)) !== null) {
            var name = text(m[1] !== undefined ? m[1] : m[2]);
            if (name && names.indexOf(name) < 0) names.push(name);
        }
        return names.join(',');
    }

    // 上游的原写法（只取第一个 actTeachers 的 name），回退用
    function firstTeacherOf(block) {
        var m = /actTeachers\s*=\s*\[\s*\{[\s\S]*?name\s*:\s*(?:"([^"]*)"|'([^']*)')/.exec(rawText(block));
        if (!m) return '';
        return text(m[1] !== undefined ? m[1] : m[2]);
    }

    // ---------- 课程名（③ 保真） ----------
    // 尾部括号里的内容是不是「课程代码」。判据只认**明显是代码**的形态：
    //   (2024123456.01) / (2024.01) / (MATH1001.01) / (课程代码)
    // 短数字（「体育(3)」「大学英语(2)」）与中文（「高等数学A(一)」）一律**当课程名的一部分保留** ——
    // 这一侧猜错的代价（课名里多一段）远小于另一侧（课名少一段），手册 §4.7 的取向是保真。
    function isCourseCode(inner) {
        var s = text(inner);
        if (/^课程?代码$/.test(s)) return true;
        if (/^[0-9]{4,}([.\-_][0-9]+)*$/.test(s)) return true;                  // 2024.01 / 2024123456.01
        if (/^[A-Z]{1,4}[0-9]{2,}([.\-_][0-9A-Za-z]+)*$/.test(s)) return true;  // MATH1001.01
        return false;
    }

    function courseNameOf(raw) {
        var name = text(raw);
        var m = /\(([^()]*)\)$/.exec(name);
        if (m && m.index > 0 && isCourseCode(m[1])) return text(name.substring(0, m.index));
        return name;
    }

    // ---------- 周次位图（② 本批统一口径） ----------
    var zeroBitSeen = false;
    var droppedOverflowWeeks = 0;
    var junkBitmapChars = 0;

    function weeksOfBitmap(bitmap) {
        var s = rawText(bitmap);
        var weeks = [];
        var i;
        var ch;
        for (i = 0; i < s.length; i++) {
            ch = s.charAt(i);
            if (ch !== '0' && ch !== '1') { junkBitmapChars++; continue; }
            if (ch !== '1') continue;
            if (i === 0) { zeroBitSeen = true; continue; }
            if (i > MAX_WEEK) { droppedOverflowWeeks++; continue; }
            weeks.push(i);
        }
        return weeks;
    }

    // 周次集合 → 极大段（手册 §4.1）：步长 1 视作每周、步长 2 视作单周 / 双周、落单的一周是 ALL。
    // 一个活动切出多个段就写成多个 block（我们的 blocks 本来就是列表，语义等价）。
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

    // ---------- index 的两种写法（⑦） ----------
    // 写法一 「index = 5 * unitCount + 2;」   → 星期 = 5 + 1、节次 = 2 + 1
    // 写法二 「index = 62;」（已算好的线性下标）→ 由 unitCount 反推同一对值
    // 一律正则解析，**不用 eval / new Function**（手册 §5 第 6 条）。
    var RE_INDEX = /index\s*=\s*(?:(\d+)\s*\*\s*(?:unitCount|(\d+))\s*\+\s*(\d+)|(\d+))\s*;/g;

    function indexesOf(scope, unitCount) {
        var out = [];
        var divisor = unitCount >= 1 ? unitCount : DEFAULT_UNIT_COUNT;
        RE_INDEX.lastIndex = 0;
        var m;
        while ((m = RE_INDEX.exec(rawText(scope))) !== null) {
            if (m[4] !== undefined) {
                var linear = parseInt(m[4], 10);
                out.push({ day: Math.floor(linear / divisor) + 1, section: (linear % divisor) + 1 });
            } else {
                out.push({ day: parseInt(m[1], 10) + 1, section: parseInt(m[3], 10) + 1 });
            }
        }
        return out;
    }

    // ---------- TaskActivity 解析（上游 parseTaskActivities 的语义） ----------
    var activities = 0;
    var activitiesSkipped = 0;
    var activitiesWithoutIndex = 0;
    var activitiesWithoutWeeks = 0;
    var droppedDay = 0;
    var droppedSection = 0;
    var activityLessons = [];

    function collectActivities(html, unitCount) {
        // 上游按 「var teachers =」 切块（教师信息在活动前面）；这里同一刀，
        // 但额外把分隔符插回每块开头，好让块里的 teachers 数组仍然可读。
        var blocks = rawText(html).split(/var\s+teachers\s*=/);
        for (var i = 1; i < blocks.length; i++) {
            var block = 'var teachers =' + blocks[i];
            var activityRegex = /new\s+TaskActivity\(([\s\S]*?)\);/g;
            var activityMatch;
            var found = [];
            while ((activityMatch = activityRegex.exec(block)) !== null) {
                found.push({ argsRaw: activityMatch[1], start: activityMatch.index, end: activityRegex.lastIndex });
            }
            if (!found.length) continue;

            var teacher = teacherNamesOf(block) || firstTeacherOf(block) || '';
            for (var a = 0; a < found.length; a++) {
                activities++;
                var args = powerSplit(found[a].argsRaw);
                if (args.length < 7) {
                    activitiesSkipped++;
                    continue;
                }

                var name = courseNameOf(args[3]);
                if (!name) {
                    name = '未知课程';
                    warn('教务课表里有课程没有课名，已按「未知课程」导入，请到教务系统核对');
                }
                var position = text(args[5]) || null;

                // args[1] 先自己试试：是字面量就用它，是 「teachers.join(",")」 这类表达式就退回
                // 同一块里 teachers 数组的名字
                var ownTeacher = text(args[1]);
                var teacherName = (ownTeacher && !looksLikeExpression(ownTeacher)) ? ownTeacher : teacher;
                if (looksLikeExpression(ownTeacher)) {
                    warn('教师的写法是「' + ownTeacher.substring(0, 40) + '」这类表达式，已改取课表里教师数组中的姓名，请核对教师');
                }

                var weeks = weeksOfBitmap(args[6]);
                if (!weeks.length) {
                    activitiesWithoutWeeks++;
                    continue;
                }

                // index 属于**这一条活动之后、下一条活动之前**的那段代码
                var nextStart = a + 1 < found.length ? found[a + 1].start : block.length;
                var scope = block.substring(found[a].end, nextStart);
                var indexes = indexesOf(scope, unitCount);
                if (!indexes.length) {
                    activitiesWithoutIndex++;
                    continue;
                }

                for (var k = 0; k < indexes.length; k++) {
                    var day = indexes[k].day;
                    var section = indexes[k].section;
                    if (!(day >= 1 && day <= MAX_DAY)) { droppedDay++; continue; }
                    if (!(section >= 1 && section <= MAX_PERIOD)) { droppedSection++; continue; }
                    activityLessons.push({
                        name: name,
                        teacher: teacherName || null,
                        position: position,
                        day: day,
                        startSection: section,
                        endSection: section,
                        weeks: weeks
                    });
                }
            }
        }
    }

    // ---------- 全局合并（上游 mergeContinuousLessons 的语义，实现换成 ES5 数组） ----------
    // 上游先按 (课名|教师|地点|星期) 分组，把「哪一周上哪几节」摊平成一个 50 x 节次 的矩阵
    // （用 Set 去重），再把每一周里的**连续节次**合并成块，最后按 (星期, 起始节, 课名) 排序输出。
    // 这里逐字对应，只是把 Set 换成对象、把排序键补全（上游的键在并列时依赖排序稳定性）。
    function mergeContinuous(lessons) {
        var order = [];
        var groups = {};
        var i;
        var j;
        var k;

        for (i = 0; i < lessons.length; i++) {
            var lesson = lessons[i];
            var key = lesson.name + SEP + (lesson.teacher || '') + SEP + (lesson.position || '') + SEP + lesson.day;
            if (!groups[key]) {
                groups[key] = {
                    name: lesson.name,
                    teacher: lesson.teacher,
                    position: lesson.position,
                    day: lesson.day,
                    cells: {}
                };
                order.push(key);
            }
            var group = groups[key];
            for (j = 0; j < lesson.weeks.length; j++) {
                var week = lesson.weeks[j];
                if (!(week >= 1 && week <= MAX_WEEK)) continue;
                var row = group.cells[week];
                if (!row) { row = {}; group.cells[week] = row; }
                for (k = lesson.startSection; k <= lesson.endSection; k++) row[k] = true;
            }
        }

        var merged = [];
        for (var g = 0; g < order.length; g++) {
            var item = groups[order[g]];
            var blockMap = {};
            var blockOrder = [];
            for (var week2 = 1; week2 <= MAX_WEEK; week2++) {
                var line = item.cells[week2];
                if (!line) continue;
                var sections = [];
                for (var s = 1; s <= MAX_PERIOD; s++) {
                    if (line[s]) sections.push(s);
                }
                if (!sections.length) continue;
                var start = sections[0];
                var prev = sections[0];
                for (var x = 1; x < sections.length; x++) {
                    var curr = sections[x];
                    if (curr === prev + 1) { prev = curr; continue; }
                    var keyDone = start + '-' + prev;
                    if (!blockMap[keyDone]) { blockMap[keyDone] = []; blockOrder.push(keyDone); }
                    blockMap[keyDone].push(week2);
                    start = curr;
                    prev = curr;
                }
                var keyLast = start + '-' + prev;
                if (!blockMap[keyLast]) { blockMap[keyLast] = []; blockOrder.push(keyLast); }
                blockMap[keyLast].push(week2);
            }

            for (var b = 0; b < blockOrder.length; b++) {
                var parts = blockOrder[b].split('-');
                merged.push({
                    name: item.name,
                    teacher: item.teacher,
                    position: item.position,
                    day: item.day,
                    startSection: parseInt(parts[0], 10),
                    endSection: parseInt(parts[1], 10),
                    weeks: blockMap[blockOrder[b]]
                });
            }
        }

        // 上游按 (星期, 起始节, 课名) 排；这里把次级键补齐，结果与排序算法的稳定性无关
        merged.sort(function (a, b) {
            if (a.day !== b.day) return cmpNum(a.day, b.day);
            if (a.startSection !== b.startSection) return cmpNum(a.startSection, b.startSection);
            var byName = cmpStr(a.name, b.name);
            if (byName !== 0) return byName;
            if (a.endSection !== b.endSection) return cmpNum(a.endSection, b.endSection);
            if (a.weeks[0] !== b.weeks[0]) return cmpNum(a.weeks[0], b.weeks[0]);
            var byTeacher = cmpStr(a.teacher || '', b.teacher || '');
            if (byTeacher !== 0) return byTeacher;
            return cmpStr(a.position || '', b.position || '');
        });
        return merged;
    }

    // ---------- 学期列表（⑪ 只读字段，不执行任何取回来的代码） ----------
    var RE_FIELD = /["']?([A-Za-z_$][A-Za-z0-9_$]*)["']?\s*:\s*("(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*'|[^,}]*)/g;
    var DATE_START_KEYS = ['startDate', 'beginDate', 'dateBegin', 'startTime', 'dateStart', 'start', 'ksrq'];
    var DATE_END_KEYS = ['endDate', 'finishDate', 'dateEnd', 'endTime', 'dateFinish', 'end', 'jsrq'];

    function unquoteJs(value) {
        var s = rawText(value).replace(/^\s+|\s+$/g, '');
        if (!s) return '';
        var first = s.charAt(0);
        if ((first === '"' || first === "'") && s.length >= 2 && s.charAt(s.length - 1) === first) {
            return s.substring(1, s.length - 1);
        }
        return s;
    }

    function matchBraceGroup(source, openIndex) {
        var depth = 0;
        var quote = '';
        var i;
        var ch;
        for (i = openIndex; i < source.length; i++) {
            ch = source.charAt(i);
            if (quote) {
                if (ch === '\\') { i++; continue; }
                if (ch === quote) quote = '';
                continue;
            }
            if (ch === '"' || ch === "'") { quote = ch; continue; }
            if (ch === '{') { depth++; continue; }
            if (ch === '}') {
                depth--;
                if (depth === 0) return i;
            }
        }
        return -1;
    }

    function fieldsOf(body) {
        var fields = {};
        RE_FIELD.lastIndex = 0;
        var m;
        while ((m = RE_FIELD.exec(body)) !== null) {
            if (fields[m[1]] === undefined) fields[m[1]] = unquoteJs(m[2]);
        }
        return fields;
    }

    function pickDate(fields, keys) {
        for (var i = 0; i < keys.length; i++) {
            var value = fields[keys[i]];
            if (value !== undefined && value !== null && value !== '') return text(value);
        }
        return '';
    }

    function semesterOf(fields) {
        var id = text(fields.id);
        if (!id) return null;
        return {
            id: id,
            schoolYear: text(fields.schoolYear || fields.xn || fields.schoolYearName),
            term: text(fields.name || fields.term || fields.xq),
            startDate: isoOfAny(pickDate(fields, DATE_START_KEYS)),
            endDate: isoOfAny(pickDate(fields, DATE_END_KEYS))
        };
    }

    // 这条接口返回的是**裸 JS 对象字面量**（键可以不带引号，外面可能还包着别的字段），
    // 所以只**读字段**，一行取回来的代码都不执行（上游用 Function("return (" + raw + ")") 求值，
    // 命中移植手册 §5 第 6 条）。做法：每条学期记录都带 id，就拿「id 这个键」当锚点 ——
    // 从它往前找到最近的 '{'、用配平法找到配对的 '}'，中间那段就是这一条记录。
    // 这样无论外层是对象、数组还是按学年分组的对象，都能取到同一层的结果。
    var RE_SEMESTER_ANCHOR = /["']?id["']?\s*:\s*["']?([0-9]+)/g;
    var semesters = [];
    var semestersUnreadable = false;
    var semesterIds = {};

    function addSemester(fields) {
        var sem = semesterOf(fields);
        if (!sem) return;
        if (semesterIds[sem.id]) return;
        semesterIds[sem.id] = true;
        semesters.push(sem);
    }

    function isWordChar(ch) {
        return /[A-Za-z0-9_$]/.test(ch);
    }

    function parseSemesters(raw) {
        var source = rawText(raw);
        if (!text(source)) return;
        var cursor = 0;
        var guard = 0;
        var match;
        RE_SEMESTER_ANCHOR.lastIndex = 0;
        while (guard < 5000) {
            guard++;
            RE_SEMESTER_ANCHOR.lastIndex = cursor;
            match = RE_SEMESTER_ANCHOR.exec(source);
            if (!match) break;
            var at = match.index;
            // 别把 semesterId 之类键名尾巴上的 id 当锚点
            if (at > 0 && isWordChar(source.charAt(at - 1))) { cursor = at + 1; continue; }
            var open = source.lastIndexOf('{', at);
            if (open < 0) break;
            var close = matchBraceGroup(source, open);
            if (close < 0) break;
            addSemester(fieldsOf(source.substring(open + 1, close)));
            // 跳到这一块的后面：块内的嵌套对象（例如学期里再套一层）不会被重复当成一条记录
            cursor = close + 1;
        }
        if (!semesters.length) semestersUnreadable = true;
    }

    function pickCurrentSemester(today) {
        var i;
        if (currentSemesterId) {
            for (i = 0; i < semesters.length; i++) {
                if (semesters[i].id === currentSemesterId) return semesters[i];
            }
        }
        if (!semesters.length) return null;
        // 没有学期 id 线索时：先用今天落在哪个学期里判，再退到「已经开学的最新的那个」，
        // 最后才退到列表里最后一个（列表按学年排，最后一条最近）
        if (today) {
            for (i = 0; i < semesters.length; i++) {
                var s = semesters[i];
                if (s.startDate && s.endDate && today >= s.startDate && today <= s.endDate) return s;
            }
            var best = null;
            for (i = 0; i < semesters.length; i++) {
                var c = semesters[i];
                if (!c.startDate || c.startDate > today) continue;
                if (!best || c.startDate > best.startDate) best = c;
            }
            if (best) return best;
        }
        var sorted = semesters.slice(0);
        sorted.sort(function (a, b) {
            if (a.schoolYear !== b.schoolYear) return cmpStr(a.schoolYear, b.schoolYear);
            if (a.startDate !== b.startDate) return cmpStr(a.startDate || '', b.startDate || '');
            return cmpStr(a.id, b.id);
        });
        return sorted[sorted.length - 1];
    }

    // ---------- 学期名（⑫） ----------
    var KIND_CN = { '1': '一', '2': '二', '3': '三', '4': '四' };

    function termLabelOf(value) {
        var label = text(value);
        if (!label) return '';
        if (label.indexOf('学期') >= 0) return label;
        if (/^[0-9]+$/.test(label)) return '第' + (KIND_CN[label] || label) + '学期';
        return '第' + label + '学期';
    }

    function todayAcademicName() {
        var year = null;
        var month = null;
        if (/^\d{4}-\d{2}/.test(todayIso)) {
            year = parseInt(todayIso.substring(0, 4), 10);
            month = parseInt(todayIso.substring(5, 7), 10);
        }
        if (year === null) {
            var now = new Date();
            year = now.getFullYear();
            month = now.getMonth() + 1;
        }
        if (month >= 9 || month === 1) {
            var start = month === 1 ? year - 1 : year;
            return start + '-' + (start + 1) + '学年第一学期';
        }
        return (year - 1) + '-' + year + '学年第二学期';
    }

    // ---------- 开学日（⑨） ----------
    function guessedFirstDay(semester) {
        var anchorYear = null;
        var anchorMonth = 2;
        var anchorDay = 20;
        var rule = '2 月 20 日所在周的周一';
        var yearText = semester ? semester.schoolYear : '';
        var label = semester ? text(semester.term) : '';
        // 第二学期在春季（按学年 +1 年），其余按秋季
        if (label.indexOf('2') >= 0 || label.indexOf('二') >= 0) {
            anchorMonth = 2;
            anchorDay = 20;
            rule = '第二学期 = 2 月 20 日所在周的周一';
            anchorYear = /^\d{4}/.test(yearText) ? parseInt(yearText.substring(0, 4), 10) + 1 : null;
        } else {
            anchorMonth = 9;
            anchorDay = 1;
            rule = '第一学期 = 9 月 1 日所在周的周一';
            anchorYear = /^\d{4}/.test(yearText) ? parseInt(yearText.substring(0, 4), 10) : null;
        }
        if (anchorYear === null) {
            var todayYear = /^\d{4}/.test(todayIso) ? parseInt(todayIso.substring(0, 4), 10) : new Date().getFullYear();
            var todayMonth = /^\d{4}-\d{2}/.test(todayIso) ? parseInt(todayIso.substring(5, 7), 10) : new Date().getMonth() + 1;
            if (todayMonth >= 2 && todayMonth <= 8) {
                anchorYear = todayYear;
                anchorMonth = 2;
                anchorDay = 20;
                rule = '没有学期信息，按今天所在学年 2 月 20 日所在周的周一推算';
            } else {
                anchorYear = todayMonth === 1 ? todayYear - 1 : todayYear;
                anchorMonth = 9;
                anchorDay = 1;
                rule = '没有学期信息，按 ' + anchorYear + ' 年 9 月 1 日所在周的周一推算';
            }
        }
        return { iso: isoOf(mondayOnOrBefore(anchorYear, anchorMonth, anchorDay)), rule: rule };
    }

    // ---------- 作息表（⑩） ----------
    function periodTimesFor(maxPeriod) {
        var out = [];
        var i;
        for (i = 0; i < SCHOOL_PERIOD_TIMES.length; i++) {
            var start = timeOf(SCHOOL_PERIOD_TIMES[i].start);
            var end = timeOf(SCHOOL_PERIOD_TIMES[i].end);
            if (start && end && minutesOf(start) < minutesOf(end)) {
                out.push({ periodIndex: out.length + 1, start: start, end: end });
            }
        }
        var added = 0;
        var last = out.length ? out[out.length - 1] : null;
        for (i = out.length + 1; i <= maxPeriod; i++) {
            if (!last) break;
            var nextStart = minutesOf(last.end) + 5;
            var nextEnd = nextStart + 45;
            if (nextEnd >= 24 * 60) break;
            last = { periodIndex: i, start: hhmmOf(nextStart), end: hhmmOf(nextEnd) };
            out.push(last);
            added++;
        }
        return { times: out, added: added, last: last };
    }

    // ---------- 主流程 ----------
    if (!text(courseHtml)) {
        throw new Error(
            '教务没有返回课表数据：登录状态可能已失效，或这个学期还没排课。' +
            '请重新登录、在课表页里确认能看到课，再点「提取课表」'
        );
    }

    var unitCountMatch = /\bunitCount\s*=\s*(\d+)/.exec(courseHtml);
    var unitCount = unitCountMatch ? parseInt(unitCountMatch[1], 10) : null;
    var unitCountGuessed = false;
    if (!(unitCount >= 1 && unitCount <= MAX_PERIOD)) {
        unitCount = DEFAULT_UNIT_COUNT;
        unitCountGuessed = true;
    }
    if (unitCountGuessed) {
        warn(
            '课表页里没读到每天节次数（unitCount），已按缺省值 ' + DEFAULT_UNIT_COUNT +
            ' 节/天解析（上游脚本同款缺省值）；节次与星期可能整体错位，请在导入预览里核对'
        );
    }

    collectActivities(courseHtml, unitCount);
    var merged = mergeContinuous(activityLessons);

    if (!merged.length) {
        throw new Error(
            '本学期没有解析到任何课程（识别到 ' + activities + ' 条排课活动：' +
            activitiesSkipped + ' 条参数不足、' + activitiesWithoutIndex + ' 条找不到节次、' +
            activitiesWithoutWeeks + ' 条没有周次、' + droppedDay + ' 条星期越界、' +
            droppedSection + ' 条节次越界）。' +
            '可能是这个学期还没排课，或教务系统改了课表页格式'
        );
    }

    // ---------- 组装课程与 block ----------
    var courseOrder = [];
    var courseMap = {};
    var maxWeek = 0;
    var maxPeriodUsed = 0;

    for (var i = 0; i < merged.length; i++) {
        var item = merged[i];
        var key = item.name + SEP + (item.teacher || '');
        if (!courseMap[key]) {
            courseMap[key] = { name: item.name, teacher: item.teacher || null, note: null, blocks: [] };
            courseOrder.push(key);
        }
        if (item.endSection > maxPeriodUsed) maxPeriodUsed = item.endSection;
        var runs = runsOf(item.weeks);
        for (var r = 0; r < runs.length; r++) {
            var run = runs[r];
            if (run.end > maxWeek) maxWeek = run.end;
            courseMap[key].blocks.push({
                dayOfWeek: item.day,
                startPeriod: item.startSection,
                endPeriod: item.endSection,
                startWeek: run.start,
                endWeek: run.end,
                weekType: run.weekType,
                location: item.position || null
            });
        }
    }

    var courses = [];
    for (var c = 0; c < courseOrder.length; c++) courses.push(courseMap[courseOrder[c]]);

    // ---------- 学期（学期名 / 开学日 / 总周数） ----------
    parseSemesters(data.semesterRaw);
    var current = pickCurrentSemester(todayIso);
    if (!semesters.length) {
        warn(
            '教务没有给出可用的学期列表' + (semestersUnreadable ? '（返回的内容没能读懂）' : '') +
            '，学期名、开学日与总周数都只能推算，请逐项核对'
        );
    } else if (!current) {
        warn('教务的学期列表里没能定位到当前学期，学期名、开学日与总周数按推算处理，请核对');
    }

    var termName = '';
    if (current) {
        var label = termLabelOf(current.term);
        var yearText = text(current.schoolYear);
        if (label && yearText) termName = yearText + '学年' + label;
        else if (label) termName = label;
    }
    if (!termName) {
        termName = '西安工业大学 ' + todayAcademicName();
        warn('教务没有给出学期名，已按导入日期推成「' + termName + '」，请到学期管理里核对');
    }

    var firstDay = current && current.startDate ? mondayOfIso(current.startDate) : null;
    if (firstDay) {
        if (firstDay !== current.startDate) {
            warn('教务的学期起始日 ' + current.startDate + ' 不是周一，第 1 周已按那一周的周一 ' + firstDay + ' 开始，请在学期管理里核对');
        }
    } else {
        var guess = guessedFirstDay(current);
        firstDay = guess.iso;
        warn('开学日期无法从教务获取，已按「' + guess.rule + '」推算为 ' + firstDay + '，请在学期管理里核对');
    }
    var totalWeeks = null;
    if (current && current.startDate && current.endDate) {
        var span = daysBetween(current.startDate, current.endDate);
        if (span !== null && span >= 0) totalWeeks = Math.floor(span / 7) + 1;
    }
    if (!(totalWeeks >= 1) || totalWeeks > MAX_WEEK) totalWeeks = null;
    if (totalWeeks === null) {
        totalWeeks = maxWeek >= 1 ? maxWeek : FALLBACK_TOTAL_WEEKS;
    }
    if (totalWeeks < maxWeek) {
        warn('课表里有第 ' + maxWeek + ' 周的课，学期总周数已从 ' + totalWeeks + ' 抬到 ' + maxWeek + '，请核对学期设置');
        totalWeeks = maxWeek;
    }
    if (totalWeeks > MAX_WEEK) totalWeeks = MAX_WEEK;

    var periods = periodTimesFor(maxPeriodUsed);
    if (periods.added > 0) {
        warn('课表用到了内置作息表没有的第 ' + (SCHOOL_PERIOD_TIMES.length + 1) + '-' + (SCHOOL_PERIOD_TIMES.length + periods.added) + ' 节，已按每节 45 分钟顺推补上，请核对上课时间');
    }
    warn('作息时间来自适配器内置的西安工业大学作息表（非教务系统读取），请对照教务处公布的作息核对');

    if (zeroBitSeen) {
        warn('周次位图里第 0 位是 1：按同族约定（位图下标即周次、下标 0 是占位符）已忽略，未产出「第 0 周」，请在导入预览里核对周次');
    }
    if (droppedOverflowWeeks > 0) {
        warn('有 ' + droppedOverflowWeeks + ' 个周次超出 1-' + MAX_WEEK + ' 周，已丢弃，请核对课表');
    }
    if (junkBitmapChars > 0) {
        warn('周次位图里有 ' + junkBitmapChars + ' 个不是 0/1 的字符，已跳过，请核对课表');
    }
    if (activitiesSkipped > 0) {
        warn('有 ' + activitiesSkipped + ' 条排课活动的参数不足 7 个（教务课表格式可能变过），已跳过，请核对课表');
    }
    if (activitiesWithoutWeeks > 0) {
        warn('有 ' + activitiesWithoutWeeks + ' 条排课活动没有周次（位图全 0 或为空），已跳过，请在教务系统里核对');
    }
    if (activitiesWithoutIndex > 0) {
        warn('有 ' + activitiesWithoutIndex + ' 条排课活动找不到节次（index），已跳过，请在教务系统里核对');
    }
    if (droppedDay > 0) {
        warn('有 ' + droppedDay + ' 条排课活动算出的星期不在 1-7（节次数 unitCount 可能与实际不符），已丢弃，请核对课表');
    }
    if (droppedSection > 0) {
        warn('有 ' + droppedSection + ' 条排课活动算出的节次超出 1-' + MAX_PERIOD + ' 节，已丢弃，请核对课表');
    }
    warn('只导入了教务当前选中的这个学期（' + termName + '），需要其它学期请在教务系统里切到那个学期再提取一次');

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
                periodTimes: periods.times,
                courses: courses
            }
        ]
    });
})()
