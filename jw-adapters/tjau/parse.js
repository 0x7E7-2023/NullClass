(function () {
    // 天津农学院教务适配器（树维 EAMS 平台）—— 第二步：教务原始数据 → 空课课表载荷。
    //
    // 移植自 shiguang_warehouse 的 TJAU/tjau.js
    //   https://github.com/XingHeYuZhuan/shiguang_warehouse  （MIT，上游 maintainer 星河欲转）
    //   上游快照 e62554a4034386b893bcd6813c7b2b64f8c730a3（2026-09-12）
    //
    // ⚠️ 本件是同族 xatu / hfnu 的**克隆链源头**（上游 XATU/myschool.js 的文件头写着「基于天津农学院
    //    适配脚本」）。三件的 powerSplit / parseTaskActivities / 位图 / idxRegex 逐字相同，只有主机、
    //    作息与提示语不同 —— 所以本件全部按 tjau **自己的**作息表（第 1 节 08:30-09:15）与主机造用例，
    //    一条也没有照抄邻件的期望值；也不把邻件的编码缺陷抄过来（见 AUDIT.md §5）。
    //
    // 上游这一段的核心逻辑（原样移植的骨架）：
    //   ① powerSplit(参数串)：按**顶层**逗号切 TaskActivity 的参数（括号深度 + 引号状态感知）
    //   ② 按 var teachers = 分块：教师取自 actTeachers 的 name，课程/教室/位图取自 TaskActivity
    //   ③ index = 星期 * unitCount + 节次（两个数都从 0 起算，所以各 +1）
    //   ④ mergeContinuousLessons：把「课名|教师|地点|星期」相同、节次连续的活动并成一个课程块
    //
    // 移植改动（逐条对照移植手册 §4 与本批检查表）：
    //   ① ES6 → ES5（去掉模板串、箭头函数、块级声明关键字、扩展运算符、Array.from / Set / endsWith；
    //      本文件是纯同步转换，一处 async/await 都不需要）
    //   ② **周次位图下标即周次**（本批统一口径）：bitmap[i] === '1' 且 i >= 1 → 第 i 周。
    //      上游 tjau 是 for (j = 0; ...) if (bitmap[j] === '1') weeks.push(j) —— **0 位为 1 时会产出
    //      「第 0 周」**，而载荷校验要求 startWeek ≥ 1，那一门课会让整个载荷被拒。本件按统一口径跳过
    //      0 位并写一条 warnings（依据见 AUDIT.md §4 第 1 条，五种读法收敛成一种）
    //   ③ **课程名保留括号**：上游 (args[3] || "未知课程").split('(')[0] 把「高等数学A(一)」砍成
    //      「高等数学A」—— 这是丢信息。本件保留原始课程名，只在括号里是**纯数字的课程序号**时摘掉
    //      （「大学物理(2)」→「大学物理」），判断依据见 AUDIT.md §4 第 3 条，用 fixtures/course-name 钉死
    //   ④ 教师 / 教室拿不到就**留空**（null）。上游写「未知教师」「未知地点」，那会被当成真名显示
    //      （手册 §4.7）；教师写成 teachers.join(",") 这类表达式时也不把表达式当名字（上游会）
    //   ⑤ **不用 eval**：上游用 Function("return (" + raw + ")")() 求值学期日历（响应体来自网络），
    //      命中移植手册 §5 第 6 条。学期日历的解析挪到了 extract.js（只做字面量扫描，不求值），
    //      本文件只读它交出来的 semester 对象
    //   ⑥ 开学日：优先用选中学期的起始日期，回退到那一周的周一（手册 §4.3）；拿不到就按
    //      第一学期 9 月 1 日 / 第二学期 2 月 20 日所在周的周一推算，**推算一定写进 warnings**（§4.2）
    //   ⑦ unitCount 读不到时用缺省 14（上游的缺省值），**同时进 warnings**（本批检查表 4）
    //   ⑧ 两种定位写法都认（本批检查表 3）：index = 3*unitCount+2 与已算好的 index = 62
    //      （后者要用 unitCount 换算，读不到 unitCount 时只能计数 + warn，不猜）。全程不用 eval
    //   ⑨ 作息表是上游 tjau.js 内置的那 11 节（第 1 节 08:30-09:15，与邻件 xatu/hfnu 的 08:00 不同），
    //      逐条过 HH:mm 与 00:00–23:59 校验；用到更晚的节次时按空课内建表补并 warn
    //   ⑩ 上游的 showToast / notifyTaskCompletion / saveImportedCourses / savePresetTimeSlots
    //      全部没有移植；本文件不碰页面、不发请求（CI 用 Rhino 实跑的那一段）

    var data = JSON.parse(typeof __ncInput === 'undefined' ? '{}' : __ncInput);
    var html = typeof data.courseTable === 'string' ? data.courseTable : '';
    var sem = data.semester && typeof data.semester === 'object' ? data.semester : null;

    var SEP = String.fromCharCode(0);        // 复合键分隔符：用 fromCharCode 取，源码里不出现控制字符
    var BACKSLASH = String.fromCharCode(92); // 判引号转义用，源码里不出现转义序列

    var MAX_WEEK = 30;              // 载荷校验：totalWeeks ∈ 1..30，startWeek/endWeek ∈ 1..totalWeeks
    var MAX_PERIOD = 20;            // 单日节次上限：超过它一定是脏数据
    var DEFAULT_UNIT_COUNT = 14;    // 上游 tjau.js 的缺省值（读不到 unitCount 时用，同时进 warnings）
    var FALLBACK_TOTAL_WEEKS = 20;  // 教务不给学期起止日期时的总周数
    var MAX_WARNINGS = 20;
    var MAX_WARNING_CHARS = 200;

    // 天津农学院作息：**上游 TJAU/tjau.js 内置的那张 11 节表**，逐条原样搬过来（第 1 节 08:30-09:15）。
    // 上游没有向教务请求作息，这张表就是脚本作者对学校的了解 —— 所以它是适配器自带的值，
    // 带进载荷的同时必须写进 warnings 说明没跟教务核对过（见 AUDIT.md §8）。
    var SCHOOL_PERIOD_TIMES = [
        { periodIndex: 1, start: '08:30', end: '09:15' },
        { periodIndex: 2, start: '09:20', end: '10:05' },
        { periodIndex: 3, start: '10:25', end: '11:10' },
        { periodIndex: 4, start: '11:15', end: '12:00' },
        { periodIndex: 5, start: '14:00', end: '14:45' },
        { periodIndex: 6, start: '14:50', end: '15:35' },
        { periodIndex: 7, start: '15:55', end: '16:40' },
        { periodIndex: 8, start: '16:45', end: '17:30' },
        { periodIndex: 9, start: '18:30', end: '19:15' },
        { periodIndex: 10, start: '19:20', end: '20:05' },
        { periodIndex: 11, start: '20:10', end: '20:55' }
    ];

    // 空课内置节次表（core:model 的 DefaultPeriodTimes，12 节）：课表用到第 12 节时用它补时间
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

    // ---------- 小工具 ----------
    function text(value) {
        if (value === null || value === undefined) return '';
        return String(value).replace(/\s+/g, ' ').replace(/^\s+|\s+$/g, '');
    }

    function pad2(n) {
        return (n < 10 ? '0' : '') + n;
    }

    function isoOf(date) {
        return date.getUTCFullYear() + '-' + pad2(date.getUTCMonth() + 1) + '-' + pad2(date.getUTCDate());
    }

    function mondayOnOrBefore(year, month, day) {
        var date = new Date(Date.UTC(year, month - 1, day));
        var offset = (date.getUTCDay() + 6) % 7;
        return new Date(date.getTime() - offset * 86400000);
    }

    function mondayOfIso(iso) {
        if (!iso) return null;
        return isoOf(mondayOnOrBefore(
            parseInt(iso.substring(0, 4), 10),
            parseInt(iso.substring(5, 7), 10),
            parseInt(iso.substring(8, 10), 10)
        ));
    }

    function epochOfIso(iso) {
        if (!iso) return null;
        return Date.UTC(
            parseInt(iso.substring(0, 4), 10),
            parseInt(iso.substring(5, 7), 10) - 1,
            parseInt(iso.substring(8, 10), 10)
        );
    }

    function isoOfAny(value) {
        var m = /(\d{4})[-/.年](\d{1,2})[-/.月](\d{1,2})/.exec(text(value));
        if (!m) return null;
        var month = parseInt(m[2], 10);
        var day = parseInt(m[3], 10);
        if (month < 1 || month > 12 || day < 1 || day > 31) return null;
        return m[1] + '-' + pad2(month) + '-' + pad2(day);
    }

    // "08:30" / "08:30:00" → "08:30"；认不出来（含 24:00 这种越界）返回 null。
    // 写进载荷的 periodTimes 必须是 00:00-23:59 的 HH:mm，越界会让整个载荷被拒。
    function timeOf(value) {
        var m = /^([01]?\d|2[0-3]):([0-5]\d)/.exec(text(value));
        if (!m) return null;
        return (m[1].length < 2 ? '0' + m[1] : m[1]) + ':' + m[2];
    }

    function minutesOf(hhmm) {
        return parseInt(hhmm.substring(0, 2), 10) * 60 + parseInt(hhmm.substring(3, 5), 10);
    }

    function cmpNum(a, b) {
        return a === b ? 0 : (a < b ? -1 : 1);
    }

    function cmpStr(a, b) {
        if (a === b) return 0;
        return a < b ? -1 : 1;
    }

    function hasOwn(obj, key) {
        return Object.prototype.hasOwnProperty.call(obj, key);
    }

    // ---------- TaskActivity 参数切分（上游 powerSplit，逐字移植成 ES5） ----------
    // 按顶层逗号切：括号 / 方括号 / 花括号深度为 0 且不在引号里时才算分隔符。
    // **与上游的一处差别**：上游在切分的同时就把每段清洗成「人看得的值」（去引号、null 变 null），
    // 那样一来「教师写成 teachers.join(",")」这种**表达式**与「"张三"」这种**字面量**在读完之后
    // 长得一模一样（都是 teachers.join(",") / 张三），分不出来。这里改成返回**未清洗**的原始段，
    // 清洗放到各个使用点（cleanArg / isLiteralArg），才能按本批检查表 2 区分两种面孔。
    function cleanArg(s) {
        var value = text(s);
        if (value === 'null' || value === 'undefined') return null;
        return value.replace(/^["']|["']$/g, '');
    }

    function powerSplit(paramsRaw) {
        var args = [];
        var current = '';
        var depth = 0;
        var inQuote = false;
        var quoteChar = '';
        var i;
        for (i = 0; i < paramsRaw.length; i++) {
            var ch = paramsRaw.charAt(i);
            if ((ch === '"' || ch === "'") && (i === 0 || paramsRaw.charAt(i - 1) !== BACKSLASH)) {
                if (!inQuote) {
                    inQuote = true;
                    quoteChar = ch;
                } else if (ch === quoteChar) {
                    inQuote = false;
                }
            }
            if (!inQuote) {
                if (ch === '(' || ch === '[' || ch === '{') depth++;
                if (ch === ')' || ch === ']' || ch === '}') depth--;
            }
            if (ch === ',' && depth === 0 && !inQuote) {
                args.push(text(current));
                current = '';
            } else {
                current += ch;
            }
        }
        args.push(text(current));
        return args;
    }

    // TaskActivity 的参数串有两种形态：
    //   ① 字面量：new TaskActivity("", "张三", "", "高等数学A(一)", "", "教1-101", "1111000...")
    //   ② 表达式：教师写成 teachers.join(",")、课程名写成 courseName + "（实验）"
    // 上游直接取 args[1] 当教师，表达式形态会把 teachers.join(",") 原样当成教师名。这里把明显不是
    // 字面量的形态判为「读不到」：教师留空（手册 §4.7），课程名只取表达式里的字符串字面量
    // （courseName + "（实验）" → 「（实验）」，比把整串表达式当课名有用）
    function isLiteralArg(raw) {
        var s = text(raw);
        if (!s) return false;
        var first = s.charAt(0);
        return first === '"' || first === "'";
    }

    // 教师：actTeachers 里取到名字就用它（上游同款）；否则只认字面量，表达式一律留空
    function teacherOfArg(raw) {
        if (raw === null || raw === undefined || raw === '') return '';
        if (isLiteralArg(raw)) return text(cleanArg(raw));
        exprTeacherHits++;
        return '';
    }

    var exprNameHits = 0;    // 课名写成表达式（只能取其中的字面量）的活动数
    var exprTeacherHits = 0; // 教师写成表达式且没能从 actTeachers 取到名字的活动数

    function nameOfArg(raw) {
        if (raw === null || raw === undefined || raw === '') return '';
        if (isLiteralArg(raw)) return cleanArg(raw);
        var literals = String(raw).match(/["'][^"']*["']/g) || [];
        exprNameHits++;
        var joined = '';
        var i;
        for (i = 0; i < literals.length; i++) joined += cleanArg(literals[i]);
        return joined;
    }

    // ---------- 课程名里的括号 ----------
    // 只摘「整段括号里全是数字」的课程序号：「大学物理(2)」→「大学物理」、「大学物理（二）(2)」→「大学物理（二）」。
    // 括号里只要有非数字字符（（一）/(一)/(听说)/A(1) 之类）就原样保留 —— 上游的 split('(')[0] 会一起砍掉，
    // 那是丢信息（见 AUDIT.md §4 第 3 条）
    var serialSuffixHits = 0;

    function courseNameOf(raw) {
        var name = text(raw);
        if (!name) return name;
        var stripped = text(name.replace(/[(（]\s*\d{1,3}\s*[)）]\s*$/, ''));
        if (stripped && stripped !== name) {
            serialSuffixHits++;
            return stripped;
        }
        return name;
    }

    // ---------- 位图周次 ----------
    var zeroBitHits = 0;      // 位图第 0 位为 1（同族约定的占位符）的活动数
    var outOfRangeWeeks = 0;  // 位图里超过第 30 周的周次个数
    var emptyBitmaps = 0;     // 位图缺失、或一个 1 都没有的活动数

    // 位图下标 i 就是第 i 周，**下标 0 是占位符**（同族 uestc / hpu / hunnu / zua / zzvcae 五件共同声明）。
    // 上游 tjau 从 0 起 push，0 位为 1 时会产出「第 0 周」，整包会被载荷校验拒掉。
    function weeksOfBitmap(bitmap) {
        var weeks = [];
        var seen = {};
        var raw = String(bitmap === null || bitmap === undefined ? '' : bitmap);
        var i;
        for (i = 0; i < raw.length; i++) {
            if (raw.charAt(i) !== '1') continue;
            if (i === 0) {
                zeroBitHits++;
                continue;
            }
            if (i > MAX_WEEK) {
                outOfRangeWeeks++;
                continue;
            }
            if (!seen[i]) {
                seen[i] = true;
                weeks.push(i);
            }
        }
        if (!weeks.length) emptyBitmaps++;
        weeks.sort(cmpNum);
        return weeks;
    }

    // ---------- 解析课表 HTML 里的 TaskActivity ----------
    var unitCountUnread = false;   // 页面里读不到 unitCount，用了缺省值
    var bareIndexes = 0;           // 已算好的 index = 62; 形态（要用 unitCount 换算）次数
    var bareWithoutUnit = 0;       // 上面那种，但 unitCount 也读不到 → 无法换算，只能计数 + warn
    var strayIndexes = 0;          // 定位数字落在 1..7 天 / 1..20 节之外，判为脏数据
    var blocksWithoutIndex = 0;    // TaskActivity 没有任何可用定位的块数

    function readUnitCount(source) {
        var m = /\bunitCount\s*=\s*(\d{1,3})\s*;/.exec(source);
        if (!m) m = /(?:var\s+)?unitCount\s*=\s*(\d{1,3})/.exec(source);
        if (!m) return null;
        var n = parseInt(m[1], 10);
        if (!(n >= 1 && n <= 60)) return null;
        return n;
    }

    function parseTaskActivities(source) {
        var lessons = [];
        var known = readUnitCount(source);
        var unitCount = known;
        if (!known) {
            unitCount = DEFAULT_UNIT_COUNT;
            unitCountUnread = true;
        }
        var blocks = source.split(/var\s+teachers\s*=/);
        var i;
        for (i = 1; i < blocks.length; i++) {
            var block = blocks[i];

            var teacherName = '';
            var teacherMatch = /actTeachers\s*=\s*\[\s*\{[\s\S]*?name:\s*"(.*?)"/.exec(block);
            if (teacherMatch) teacherName = text(teacherMatch[1]);

            var activityMatch = /new\s+TaskActivity\(([\s\S]*?)\);/.exec(block);
            if (!activityMatch) continue;

            var args = powerSplit(activityMatch[1]);
            if (!teacherName) teacherName = teacherOfArg(args[1]);

            var courseName = courseNameOf(nameOfArg(args[3]));
            // 教室：上游会整段删掉括号内容（replace(/\(.*?\)/g, "")），那会把「综合楼B101(东)」砍成
            // 「综合楼B101」—— 这里只剥成对的引号并保留括号内容，整串为空时才留空
            var location = cleanArg(args[5]) === null ? '' : text(cleanArg(args[5]));
            var weeks = weeksOfBitmap(args[6] === null || args[6] === undefined ? '' : cleanArg(args[6]));

            // 定位一：index = 星期 * unitCount + 节次;
            var located = 0;
            var idxRegex = /index\s*=\s*(\d+)\s*\*\s*unitCount\s*\+\s*(\d+)\s*;/g;
            var m;
            while ((m = idxRegex.exec(block)) !== null) {
                located++;
                pushLesson(lessons, courseName, teacherName, location,
                    parseInt(m[1], 10) + 1, parseInt(m[2], 10) + 1, weeks);
            }

            // 定位二：index = 62;（上游不认这种写法，同族的 HPU 用 unitCount 换算）
            var rest = block.replace(/index\s*=\s*\d+\s*\*\s*unitCount\s*\+\s*\d+\s*;/g, '');
            var bareRegex = /index\s*=\s*(\d+)\s*;/g;
            while ((m = bareRegex.exec(rest)) !== null) {
                located++;
                if (!known) {
                    bareWithoutUnit++;
                    continue;
                }
                var linear = parseInt(m[1], 10);
                bareIndexes++;
                pushLesson(lessons, courseName, teacherName, location,
                    Math.floor(linear / unitCount) + 1, (linear % unitCount) + 1, weeks);
            }

            if (!located) blocksWithoutIndex++;
        }
        return lessons;
    }

    function pushLesson(lessons, name, teacher, location, day, period, weeks) {
        if (!name) return;
        if (day < 1 || day > 7) {
            strayIndexes++;
            return;
        }
        if (period < 1 || period > MAX_PERIOD) {
            strayIndexes++;
            return;
        }
        lessons.push({
            name: name,
            teacher: teacher,
            location: location,
            day: day,
            period: period,
            weeks: weeks
        });
    }

    // ---------- 上游的 mergeContinuousLessons（原样移植成确定性的写法） ----------
    // 上游用 Set / Array.from / 对象键序 / localeCompare，这里全部换成显式数组与码位比较，
    // 保证 Rhino 与 V8 给出同一份结果（fixture 是逐数组比对的）。语义不变：
    //   每周 → 该周上这门课的节次集合 → 同一周里连续的节次并成一段（1-2 节 + 3-4 节 → 1-4 节）→
    //   同一段节次把这些周合起来（1-8 周 + 9-16 周 → 1-16 周）
    function mergeContinuousLessons(lessons) {
        if (!lessons || !lessons.length) return [];

        var groupOrder = [];
        var groups = {};
        var i;
        for (i = 0; i < lessons.length; i++) {
            var lesson = lessons[i];
            var key = lesson.name + SEP + lesson.teacher + SEP + lesson.location + SEP + lesson.day;
            if (!groups[key]) {
                groups[key] = {
                    name: lesson.name,
                    teacher: lesson.teacher,
                    location: lesson.location,
                    day: lesson.day,
                    periods: [],
                    weeks: []
                };
                groupOrder.push(key);
            }
            groups[key].periods.push(lesson.period);
            groups[key].weeks.push(lesson.weeks);
        }
        groupOrder.sort();

        var blockMap = {};
        var blockOrder = [];
        for (i = 0; i < groupOrder.length; i++) {
            var group = groups[groupOrder[i]];
            // 第 N 周 → 这一周上这门课的节次集合（等价于上游的 Set，但顺序确定）
            var weekNumbers = [];
            var cells = {};
            var j;
            var k;
            for (j = 0; j < group.periods.length; j++) {
                var weeks = group.weeks[j];
                for (k = 0; k < weeks.length; k++) {
                    var week = weeks[k];
                    if (week < 1 || week > MAX_WEEK) continue;
                    if (!cells[week]) {
                        cells[week] = {};
                        weekNumbers.push(week);
                    }
                    cells[week][group.periods[j]] = true;
                }
            }
            weekNumbers.sort(cmpNum);

            for (j = 0; j < weekNumbers.length; j++) {
                var weekNo = weekNumbers[j];
                var periods = [];
                for (var p in cells[weekNo]) {
                    if (hasOwn(cells[weekNo], p)) periods.push(parseInt(p, 10));
                }
                periods.sort(cmpNum);
                var start = periods[0];
                var prev = periods[0];
                for (k = 1; k < periods.length; k++) {
                    if (periods[k] === prev + 1) {
                        prev = periods[k];
                        continue;
                    }
                    addBlock(blockMap, blockOrder, group, start, prev, weekNo);
                    start = periods[k];
                    prev = periods[k];
                }
                addBlock(blockMap, blockOrder, group, start, prev, weekNo);
            }
        }

        var out = [];
        for (i = 0; i < blockOrder.length; i++) {
            var item = blockMap[blockOrder[i]];
            out.push({
                name: item.name,
                teacher: item.teacher,
                location: item.location,
                day: item.day,
                startSection: item.start,
                endSection: item.end,
                weeks: item.weeks
            });
        }
        return out;
    }

    function addBlock(blockMap, blockOrder, group, start, end, week) {
        var key = group.name + SEP + group.teacher + SEP + group.location + SEP + group.day + SEP +
            start + SEP + end;
        if (!blockMap[key]) {
            blockMap[key] = {
                name: group.name,
                teacher: group.teacher,
                location: group.location,
                day: group.day,
                start: start,
                end: end,
                weeks: []
            };
            blockOrder.push(key);
        }
        if (blockMap[key].weeks.indexOf(week) < 0) blockMap[key].weeks.push(week);
    }

    // 周次集合 → 极大段（手册 §4.1）：步长 1 是每周、步长 2 是单/双周
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

    var merged = mergeContinuousLessons(parseTaskActivities(html));

    // ---------- 课程与 block ----------
    // 课程顺序按课名|教师排序（不依赖合并结果的对象键序，也不依赖排序算法的稳定性）
    var courseOrder = [];
    var byCourse = {};
    var i2;
    for (i2 = 0; i2 < merged.length; i2++) {
        var item = merged[i2];
        var key = item.name + SEP + item.teacher;
        if (!byCourse[key]) {
            byCourse[key] = { name: item.name, teacher: item.teacher, note: null, blocks: [], seen: {} };
            courseOrder.push(key);
        }
    }
    courseOrder.sort();

    var maxWeek = 0;
    var maxPeriod = 0;
    for (i2 = 0; i2 < merged.length; i2++) {
        var entry = merged[i2];
        var course = byCourse[entry.name + SEP + entry.teacher];
        if (!course) continue;
        var runList = runsOf(entry.weeks);
        for (var r = 0; r < runList.length; r++) {
            var run = runList[r];
            if (run.end > maxWeek) maxWeek = run.end;
            if (entry.endSection > maxPeriod) maxPeriod = entry.endSection;
            var blockKey = entry.day + '|' + entry.startSection + '|' + entry.endSection + '|' +
                run.start + '|' + run.end + '|' + run.weekType + '|' + entry.location;
            if (course.seen[blockKey]) continue;   // 完全重复的安排只留一条
            course.seen[blockKey] = true;
            course.blocks.push({
                dayOfWeek: entry.day,
                startPeriod: entry.startSection,
                endPeriod: entry.endSection,
                startWeek: run.start,
                endWeek: run.end,
                weekType: run.weekType,
                location: entry.location ? entry.location : null
            });
        }
    }

    var courses = [];
    for (i2 = 0; i2 < courseOrder.length; i2++) {
        var built = byCourse[courseOrder[i2]];
        built.blocks.sort(function (a, b) {
            return cmpNum(a.dayOfWeek, b.dayOfWeek) || cmpNum(a.startPeriod, b.startPeriod) ||
                cmpNum(a.endPeriod, b.endPeriod) || cmpNum(a.startWeek, b.startWeek) ||
                cmpNum(a.endWeek, b.endWeek) || cmpStr(a.weekType, b.weekType) ||
                cmpStr(a.location || '', b.location || '');
        });
        courses.push({
            name: built.name,
            teacher: built.teacher ? built.teacher : null,
            note: built.note,
            blocks: built.blocks
        });
    }

    if (!courses.length) {
        throw new Error(
            '没有从教务返回的课表里解析出课程（响应 ' + html.length + ' 个字符）：' +
            '可能这个学期还没排课，也可能登录状态已失效。请重新登录、在教务页面里确认能看到课表后再点「提取课表」'
        );
    }

    // ---------- 学期名 / 开学日 / 总周数 ----------
    var KIND_CN = { '1': '一', '2': '二', '3': '三', '4': '四' };

    function termYearLabel() {
        if (sem && text(sem.schoolYear)) return text(sem.schoolYear);
        var todayIso = isoOfAny(data.today);
        if (todayIso) {
            var y = parseInt(todayIso.substring(0, 4), 10);
            if (y) return y + '-' + (y + 1);
        }
        return '当前学年';
    }

    // 学期名用教务自己的「学年 + 第几学期」（手册 §4.7：别拿适配器名当学期名）
    function termNameOf() {
        if (sem) {
            var label = text(sem.rawName);
            if (label && label.indexOf('学期') >= 0) return label;
            var year = text(sem.schoolYear);
            var cn = KIND_CN[label] || label;
            if (year && cn) return year + '学年第' + cn + '学期';
            if (year) return year + '学年' + label;
        }
        return '天津农学院 ' + termYearLabel() + '学年';
    }

    var name0 = termNameOf();

    // 开学日：选中学期的起始日期 → 回退到那一周的周一（手册 §4.3）；拿不到就按学期锚点推算
    var calendarStart = sem ? isoOfAny(sem.startDate) : null;
    var calendarEnd = sem ? isoOfAny(sem.endDate) : null;
    var startInfo;
    var academicYear = sem && /^[0-9]{4}/.test(text(sem.schoolYear))
        ? parseInt(text(sem.schoolYear).substring(0, 4), 10) : 0;
    var kind = sem ? text(sem.rawName) : '';
    if (calendarStart) {
        startInfo = {
            iso: mondayOfIso(calendarStart),
            rule: '教务给的学期起始日期（' + calendarStart + '）所在周的周一',
            source: '教务'
        };
    } else {
        var todayIso = isoOfAny(data.today);
        if (!academicYear && todayIso) academicYear = parseInt(todayIso.substring(0, 4), 10);
        if (!academicYear) academicYear = new Date().getUTCFullYear();
        var isSecond = kind === '2';
        startInfo = {
            iso: isoOf(mondayOnOrBefore(academicYear + (isSecond ? 1 : 0), isSecond ? 2 : 9, isSecond ? 20 : 1)),
            rule: (isSecond ? '第二学期 2 月 20 日' : '第一学期 9 月 1 日') +
                '所在周的周一（' + academicYear + ' 学年）',
            source: '推算'
        };
    }

    // 总周数：教务给了学期起止日期就用它的周数，否则用缺省 20；
    // 课表里更晚的周次必须放得下（否则那些 block 越界、整包被拒），抬高时单独说明
    var calendarWeeks = 0;
    if (calendarStart && calendarEnd) {
        var from = epochOfIso(mondayOfIso(calendarStart));
        var to = epochOfIso(calendarEnd);
        if (from !== null && to !== null && to >= from) {
            calendarWeeks = Math.ceil((Math.floor((to - from) / 86400000) + 1) / 7);
        }
        if (calendarWeeks > MAX_WEEK) calendarWeeks = MAX_WEEK;
        if (calendarWeeks < 1) calendarWeeks = 0;
    }
    var totalWeeks = calendarWeeks > 0 ? calendarWeeks : FALLBACK_TOTAL_WEEKS;
    var raisedBySchedule = false;
    var clampedWeeks = false;
    if (maxWeek > totalWeeks) {
        totalWeeks = maxWeek;
        raisedBySchedule = true;
    }
    if (totalWeeks > MAX_WEEK) {
        totalWeeks = MAX_WEEK;
        clampedWeeks = true;
    }
    if (totalWeeks < 1) totalWeeks = FALLBACK_TOTAL_WEEKS;

    // ---------- 作息时间 ----------
    var periodTimes = [];
    var badSlots = 0;
    for (i2 = 0; i2 < SCHOOL_PERIOD_TIMES.length; i2++) {
        var slot = SCHOOL_PERIOD_TIMES[i2];
        var slotStart = timeOf(slot.start);
        var slotEnd = timeOf(slot.end);
        if (!slotStart || !slotEnd || minutesOf(slotStart) >= minutesOf(slotEnd)) {
            badSlots++;
            continue;
        }
        periodTimes.push({ periodIndex: slot.periodIndex, start: slotStart, end: slotEnd });
    }
    var tableLength = periodTimes.length;
    var extendedTo = 0;
    var uncoveredFrom = 0;
    for (var p2 = tableLength + 1; p2 <= maxPeriod; p2++) {
        var fallbackSlot = BUILTIN_PERIOD_TIMES[p2 - 1];
        if (fallbackSlot) {
            periodTimes.push({ periodIndex: p2, start: fallbackSlot.start, end: fallbackSlot.end });
            extendedTo = p2;
        } else if (!uncoveredFrom) {
            uncoveredFrom = p2;
        }
    }

    // ---------- warnings ----------
    var allWarnings = [];

    function warn(message) {
        var line = String(message);
        if (line.length > MAX_WARNING_CHARS) line = line.substring(0, MAX_WARNING_CHARS - 1) + '…';
        allWarnings.push(line);
    }

    var BASES = {
        current: '教务学期日历标出的当前学期',
        date: '起止日期包含今天的那个学期',
        latest: '起始日期最晚的那个学期',
        last: '学期列表里的最后一项',
        page: '课表页上学期组件里的值'
    };

    warn(
        '只导入了教务系统的一个学期（' + name0 + '，按' + (BASES[sem && sem.source] || '教务给出的学期') +
        '自动选中）；要导入别的学期，请在教务页面里切到那个学期再点「提取课表」'
    );

    if (startInfo.source === '教务') {
        warn('开学日期取自' + startInfo.rule + '：' + startInfo.iso + '，请在学期管理里核对成学校实际开学日');
    } else {
        warn(
            '教务没有给出这个学期的起止日期，第 1 周按「' + startInfo.rule + '」推算为 ' + startInfo.iso +
            '，请在学期管理里核对成学校实际开学日'
        );
    }

    var weeksSource = calendarWeeks > 0
        ? ('教务给出的学期起止日期（' + calendarWeeks + ' 周）')
        : ('适配器内置的 ' + FALLBACK_TOTAL_WEEKS + ' 周（教务没有给出学期起止日期）');
    if (raisedBySchedule) {
        warn(
            '学期总周数用的是' + weeksSource + '，但课表里有第 ' + maxWeek + ' 周的课，已按 ' + totalWeeks +
            ' 周导入（否则那几周的课放不下），如与实际不符可在学期管理里改'
        );
    } else {
        warn(
            '学期总周数用的是' + weeksSource + (clampedWeeks ? '，已按载荷上限 ' + MAX_WEEK + ' 周截断' : '') +
            '，如与实际不符可在学期管理里改'
        );
    }

    warn(
        '作息时间用的是适配器内置的天津农学院 ' + tableLength + ' 节作息表（第 1 节 ' +
        SCHOOL_PERIOD_TIMES[0].start + '-' + SCHOOL_PERIOD_TIMES[0].end +
        '，来自上游脚本的内置表，不是从教务读的），如与学校实际作息不符请在学期管理里改'
    );

    if (unitCountUnread) {
        warn(
            '课表响应里没有读到 unitCount（一周的节次数），已按上游脚本的缺省值 ' + DEFAULT_UNIT_COUNT +
            ' 换算星期与节次；如果课表整体错位，请把这条反馈给我们'
        );
    }
    if (bareWithoutUnit > 0) {
        warn(
            '有 ' + bareWithoutUnit + ' 处课表定位是已算好的数字（index = 62 这种）而 unitCount 也读不到，' +
            '没法换算成星期与节次，这些课没有导进来，请反馈'
        );
    } else if (bareIndexes > 0) {
        warn(
            '有 ' + bareIndexes + ' 处课表定位是已算好的数字（index = 62 这种），已按 unitCount 换算成星期与节次，请核对'
        );
    }
    if (strayIndexes > 0) {
        warn(
            '有 ' + strayIndexes + ' 处课表定位换算出来的星期或节次超出正常范围（星期 1-7、节次 1-' +
            MAX_PERIOD + '），已跳过这些定位，请反馈'
        );
    }
    if (blocksWithoutIndex > 0) {
        warn('有 ' + blocksWithoutIndex + ' 个课程块里没有任何能识别的课表定位（index = ...），这些课没有导进来，请反馈');
    }
    if (zeroBitHits > 0) {
        warn(
            '有 ' + zeroBitHits + ' 个活动的周次位图第 0 位是 1（同族约定里这一位是占位符，不是第 0 周），' +
            '已按忽略处理，请在导入预览里核对这些课的周次'
        );
    }
    if (outOfRangeWeeks > 0) {
        warn('有 ' + outOfRangeWeeks + ' 个周次超出 1-' + MAX_WEEK + ' 周，已丢弃（教务给出的周次不正常）');
    }
    if (emptyBitmaps > 0) {
        warn('有 ' + emptyBitmaps + ' 个活动的周次位图是空的或一个 1 都没有，这些课没有可导入的周次，已跳过');
    }
    if (serialSuffixHits > 0) {
        warn(
            '有 ' + serialSuffixHits + ' 个课程名末尾括号里是纯数字（按同族约定判为教学班序号），已摘掉；' +
            '其余带括号的课程名原样保留，请核对'
        );
    }
    if (exprNameHits > 0) {
        warn('有 ' + exprNameHits + ' 个课程的课名字段是拼接表达式而不是字面量，已按其中的字符串字面量取值，请核对');
    }
    if (exprTeacherHits > 0) {
        warn(
            '有 ' + exprTeacherHits + ' 个活动的教师字段是拼接表达式（例如 teachers.join(",")）且没有取到教师名单，' +
            '已留空教师而不是把表达式当名字，请核对'
        );
    }
    if (maxPeriod > tableLength) {
        var notes = [];
        if (extendedTo) {
            notes.push(extendedTo > tableLength + 1
                ? ('第 ' + (tableLength + 1) + '-' + extendedTo + ' 节按空课内建节次表补了时间')
                : ('第 ' + extendedTo + ' 节按空课内建节次表补了时间'));
        }
        if (uncoveredFrom) notes.push('第 ' + uncoveredFrom + ' 节及之后没有可用的作息时间');
        warn(
            '课表里用到第 ' + maxPeriod + ' 节，而内置作息表只到第 ' + tableLength + ' 节：' +
            notes.join('；') + '，请核对'
        );
    }
    if (badSlots > 0) {
        warn('适配器内置作息表里有 ' + badSlots + ' 节的时间不合法，已丢弃这些节次，请反馈');
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
                name: name0,
                firstDay: startInfo.iso,
                totalWeeks: totalWeeks,
                periodTimes: periodTimes,
                courses: courses
            }
        ]
    });
})()
