(function () {
    // 合肥师范学院教务适配器（树维 EAMS 平台）—— 第二步：纯转换。
    //
    // 移植自 shiguang_warehouse 的 HFNU/hfnu.js
    //   https://github.com/XingHeYuZhuan/shiguang_warehouse  （MIT，作者 星河欲转）
    //   上游快照 e62554a4034386b893bcd6813c7b2b64f8c730a3（2026-09-12）
    //
    // 平台：树维 EAMS（/eams/，上海树维信息科技有限公司 SupWisdom，新开普子公司）——
    // **不是强智**。强智的路径是 /jsxsd/、登录页署名「湖南强智科技发展有限公司」；
    // 树维的登录页（含本校 loginExt.action）署名「上海树维信息科技有限公司」。
    //
    // 上游在同一段脚本里请求接口、解析课表、算周次、推作息、调桥保存；这里只做纯转换
    // （不碰页面、不发请求，CI 里用 Rhino 实跑）。取数全部在 extract.js 里，交出来的就是
    // 教务的原始课表 HTML 与学期日历。
    //
    // 移植改动（逐条，按移植手册 §4 与本批检查表）：
    //   ① ES6 → ES5（去掉模板串、箭头函数、块级声明关键字、扩展语法；本来就无 async/await）
    //   ② 上游用 Function("return (" + raw + ")") 解析 dataQuery.action 的学期日历 —— 那是
    //      把**网络取回来的字符串**当代码执行，命中手册 §5 第 6 条。这里改成
    //      JSON.parse + 一次「去掉外层圆括号」的宽松处理，取不到就交 null 走推算
    //   ③ 上游用 window.shiguangBridgePromise.showSingleSelection 问用户「选哪个学期」与
    //      「选哪个校区」；这里不再弹窗：
    //        · 学期 → 取教务 calendar 的当前学期（extract.js 负责）。
    //        · 校区 → 由页面线索自动判定（见 §「校区作息」一节），判不出来时**不静默选一套**，
    //          而是用锦绣校区那张并在 warnings 里如实说明判不出来的原因
    //   ④ 周次位图：上游 for (j = 0; j < len; j++) if (bitmap[j] === '1') weeks.push(j)
    //      **会把第 0 位当成第 0 周**。本适配器按本批（批次四）的统一口径实现 ——
    //      bitmap[i] === '1' 且 i >= 1 才是第 i 周，bitmap[0] === '1' 不产出周次、
    //      改为写一条 warnings。依据与本批同族五件（uestc / hpu / hunnu / zua / zzvcae）
    //      共同声明的「下标即周次、0 位是占位符」。详见 AUDIT.md 的「位图 0 位口径」一节
    //   ⑤ 教师：上游用一个初始值写死成「未知教师」的变量 + 从别处找，最后写进课程的也是
    //      「未知教师」；本移植件改成 —— 优先取同一条安排自己的参数，取不到再从**它所属的
    //      var teachers / var actTeachers 姓名块**里取（上游就是这么找的，只是找了不写），
    //      再取不到就**留空**（手册 §4.7：写「未知」会被课表当成真名显示）
    //   ⑥ 教室：上游 (args[5] || "未知地点").replace(/\(.*?\)/g, "")。这里保留「剥括号」，
    //      但取不到时**留空**而不是写「未知地点」
    //   ⑦ unitCount：上游缺省 14 且不告诉任何人。这里读不到时仍然按 14 反推，但**同时进
    //      warnings**（节次数是猜的），不许静默
    //   ⑧ index 寻址：上游只认 index = 星期 * unitCount + 节次 这种带变量的写法，
    //      已算好的裸数字（index = 62;）会被**静默丢掉**。这里补上裸数字分支，
    //      按页面上的 unitCount 反推星期与节次，并进 warnings（见 AUDIT.md）
    //   ⑨ 开学日与总周数：上游没有（它只存课程与作息）。这里用 semesterCalendar 给的
    //      学期起止日期 —— 开学日取学期起始日**所在周的周一**（手册 §4.3，缺省周一），
    //      总周数取起止跨的自然周数；拿不到就回退（开学日 → 最近的那个周一，总周数 → 20），
    //      **两条回退都必须出现在 warnings 里**
    //   ⑩ 学期名：上游没有。这里用教务的学年 + 学期（「2026-2027学年第一学期」），
    //      拿不到才用「合肥师范学院 + 学年学期」（手册 §4.7：别拿适配器名当学期名）
    //   ⑪ 课程合并（上游的 mergeContinuousLessons + 按周矩阵重组节次块）原样移植，只把
    //      最后的排序换成确定的比较函数并补全次级键 —— 上游用 localeCompare 排中文，
    //      Rhino 与 V8 的结果未必一致，而 fixture 是**逐数组比对**的
    //   ⑫ 上游的 showToast / notifyTaskCompletion / saveImportedCourses /
    //      savePresetTimeSlots 这些桥调用全部没有移植（我们这条链路是「拉」不是「推」）
    //   ⑬ 学期是自动挑的（extract.js 挑好交过来，见那边文件头 ③），挑法也如实带出去：
    //      pickedBy 是 today / last 时各写一条 warnings —— 这两种情况可能挑错学期，
    //      用户看到这一句才有机会发现（上游是弹窗让用户选，前提是用户看得见自己选了什么）

    var data = JSON.parse(typeof __ncInput === 'undefined' ? '{}' : __ncInput);
    var courseHtml = data.courseHtml === null || data.courseHtml === undefined ? '' : String(data.courseHtml);
    var semester = data.semester || {};
    var pageProbe = data.page || {};
    var bootProbe = data.bootstrap || {};

    var SCHOOL = '合肥师范学院';
    var SEP = String.fromCharCode(0);        // 复合键分隔符：用 fromCharCode 取，源码里不出现控制字符
    var UNIT_FALLBACK = 14;                  // 上游 hfnu.js 写死的 unitCount 缺省值
    var MAX_WEEK = 30;                       // 载荷校验：totalWeeks 与 startWeek/endWeek 都在 1..30
    var MAX_PERIOD = 20;                     // 单日节次上限：超过它一定是脏数据
    var FALLBACK_TOTAL_WEEKS = 20;            // 拿不到学期日历时用的总周数（上游同族常见的缺省）
    var MAX_WARNINGS = 20;
    var MAX_WARNING_CHARS = 200;

    var CAMPUS_JINXIU = '锦绣校区';
    var CAMPUS_BINHU = '滨湖校区';

    // ---------- 两套校区作息（上游 hfnu.js 的 timeSlot1 / timeSlot2，逐条原样） ----------
    // 上游把这两套写死在脚本里、靠弹窗让用户选；本移植件的判定规则见下面 campus() 一节。
    // 两张表都必须过 HH:mm 与 00:00-23:59 校验（越界会让整个载荷被拒，不是跳过一节）。
    var CAMPUS_TABLES = [
        {
            name: CAMPUS_JINXIU,
            slots: [
                { periodIndex: 1, start: '08:00', end: '08:45' },
                { periodIndex: 2, start: '08:55', end: '09:40' },
                { periodIndex: 3, start: '09:55', end: '10:40' },
                { periodIndex: 4, start: '10:50', end: '11:35' },
                { periodIndex: 5, start: '14:10', end: '14:55' },
                { periodIndex: 6, start: '15:05', end: '15:50' },
                { periodIndex: 7, start: '16:05', end: '16:50' },
                { periodIndex: 8, start: '17:00', end: '17:45' },
                { periodIndex: 9, start: '19:00', end: '19:45' },
                { periodIndex: 10, start: '19:50', end: '20:35' },
                { periodIndex: 11, start: '20:40', end: '21:25' }
            ]
        },
        {
            name: CAMPUS_BINHU,
            slots: [
                { periodIndex: 1, start: '08:20', end: '09:05' },
                { periodIndex: 2, start: '09:10', end: '09:55' },
                { periodIndex: 3, start: '10:05', end: '10:50' },
                { periodIndex: 4, start: '10:55', end: '11:40' },
                { periodIndex: 5, start: '13:40', end: '14:25' },
                { periodIndex: 6, start: '14:30', end: '15:15' },
                { periodIndex: 7, start: '15:25', end: '16:10' },
                { periodIndex: 8, start: '16:15', end: '17:00' },
                { periodIndex: 9, start: '18:30', end: '19:15' },
                { periodIndex: 10, start: '19:20', end: '20:05' },
                { periodIndex: 11, start: '20:10', end: '20:55' }
            ]
        }
    ];

    // 空课内置节次表（:core:model 的 DefaultPeriodTimes，12 节）：课表用到的节次超出校区作息表时
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

    // ---------- 小工具 ----------
    function text(value) {
        if (value === null || value === undefined) return '';
        return String(value);
    }

    function tidy(value) {
        return text(value).replace(/\s+/g, ' ').trim();
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
    // 认不到再在串里找（"2026年9月7日" / "2026/09/07" 这类）。
    function isoOfAny(value) {
        var s = tidy(value);
        var m = /^(\d{4})[-/.年](\d{1,2})[-/.月](\d{1,2})/.exec(s);
        if (!m) m = /(\d{4})[-/.年](\d{1,2})[-/.月](\d{1,2})/.exec(s);
        if (!m) return null;
        var month = parseInt(m[2], 10);
        var day = parseInt(m[3], 10);
        if (month < 1 || month > 12 || day < 1 || day > 31) return null;
        return m[1] + '-' + pad2(month) + '-' + pad2(day);
    }

    function dayNumber(iso) {
        return Date.UTC(
            parseInt(iso.substring(0, 4), 10),
            parseInt(iso.substring(5, 7), 10) - 1,
            parseInt(iso.substring(8, 10), 10)
        ) / 86400000;
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

    // "08:00" / "08:00:00" → "08:00"；认不出来（含 24:00 这种越界）返回 null
    function timeOf(value) {
        var m = /^([01]?\d|2[0-3]):([0-5]\d)/.exec(tidy(value));
        if (!m) return null;
        return (m[1].length < 2 ? '0' + m[1] : m[1]) + ':' + m[2];
    }

    function minutesOf(hhmm) {
        return parseInt(hhmm.substring(0, 2), 10) * 60 + parseInt(hhmm.substring(3, 5), 10);
    }

    // 确定性的比较函数（不用 localeCompare：它排中文的结果跟引擎有关，而 fixture 逐数组比对）
    function cmpStr(a, b) {
        if (a === b) return 0;
        return a < b ? -1 : 1;
    }

    function cmpNum(a, b) {
        return a === b ? 0 : (a < b ? -1 : 1);
    }

    // ---------- 校区判定 ----------
    // 收集到的全部线索（都与代码实际读到的字段一一对应，AUDIT.md §「校区作息」里逐条列了）：
    //   a. 页面上已勾选的校区单选按钮（校区切换控件本身，最直接）
    //   b. 当前页面正文里出现的校区名（只出现一个时才算线索）
    //   c. 课表页 HTML 里「第N节」表头旁括号中的时间与哪一套表对得上
    //   d. 课程教室 / 地点文本里的校区名（全部指向同一个时才算线索）
    function campusTokenOf(value) {
        var s = tidy(value);
        if (s.indexOf('锦绣') >= 0) return CAMPUS_JINXIU;
        if (s.indexOf('滨湖') >= 0) return CAMPUS_BINHU;
        return '';
    }

    function tokensOf(probe) {
        var toks = probe && probe.tokens ? probe.tokens : {};
        var names = {};
        var i;
        for (i = 0; i < CAMPUS_TABLES.length; i++) names[CAMPUS_TABLES[i].name] = false;
        if (toks[CAMPUS_JINXIU] || toks.jinxiu) names[CAMPUS_JINXIU] = true;
        if (toks[CAMPUS_BINHU] || toks.binhu) names[CAMPUS_BINHU] = true;
        // 有的部署把校区名写在别的字段里，兜底再扫一遍序列化后的文本
        var raw = JSON.stringify(toks);
        for (i = 0; i < CAMPUS_TABLES.length; i++) {
            if (raw.indexOf(CAMPUS_TABLES[i].name) >= 0) names[CAMPUS_TABLES[i].name] = true;
        }
        return names;
    }

    function plainToken(names) {
        var found = [];
        for (var i = 0; i < CAMPUS_TABLES.length; i++) {
            if (names[CAMPUS_TABLES[i].name]) found.push(CAMPUS_TABLES[i].name);
        }
        return found.length === 1 ? found[0] : '';
    }

    // 把「第N节 08:00-08:45」这类表头线索归一成 [{periodIndex, start, end}]
    function sectionTimesOf(probe) {
        var raw = probe && probe.sectionTimes ? probe.sectionTimes : [];
        var out = [];
        var i;
        for (i = 0; i < raw.length; i++) {
            var item = raw[i] || {};
            var number = parseInt(item.periodIndex !== undefined && item.periodIndex !== null
                ? item.periodIndex : item.number, 10);
            var start = timeOf(item.start);
            var end = timeOf(item.end);
            if (!(number >= 1 && number <= MAX_PERIOD) || !start || !end) continue;
            if (minutesOf(start) >= minutesOf(end)) continue;
            out.push({ periodIndex: number, start: start, end: end });
        }
        out.sort(function (a, b) { return a.periodIndex - b.periodIndex; });
        return out;
    }

    // 表头时间与某套校区表对得上的节数
    function timeHits(table, sectionTimes) {
        var hits = 0;
        var i;
        var j;
        for (i = 0; i < sectionTimes.length; i++) {
            for (j = 0; j < table.slots.length; j++) {
                if (table.slots[j].periodIndex !== sectionTimes[i].periodIndex) continue;
                if (table.slots[j].start === sectionTimes[i].start && table.slots[j].end === sectionTimes[i].end) hits++;
            }
        }
        return hits;
    }

    function tableByName(name) {
        for (var i = 0; i < CAMPUS_TABLES.length; i++) {
            if (CAMPUS_TABLES[i].name === name) return CAMPUS_TABLES[i];
        }
        return null;
    }

    function campusPlan(rooms) {
        var notes = [];
        var picked = '';
        var rule = '';
        var conflict = '';

        // a. 已勾选的校区单选按钮
        // 不用 instanceof Array：Rhino 与浏览器里的 Array 未必是同一个全局对象
        var radios = (pageProbe.radios && typeof pageProbe.radios === 'object' &&
            typeof pageProbe.radios.length === 'number') ? pageProbe.radios : [];
        var i;
        var checked = [];
        for (i = 0; i < radios.length; i++) {
            var radio = radios[i] || {};
            if (!radio.checked) continue;
            var token = campusTokenOf(text(radio.value) + ' ' + text(radio.label));
            if (token && checked.indexOf(token) < 0) checked.push(token);
        }
        if (checked.length === 1) {
            picked = checked[0];
            rule = '页面上勾选的校区按钮';
        } else if (checked.length > 1) {
            conflict = '页面上有多个校区按钮同时处于勾选状态';
        } else {
            notes.push('页面上没有已勾选的校区按钮');
        }

        // b. 当前页面正文里的校区名（只出现一个才算）
        var pageToken = plainToken(tokensOf(pageProbe));
        var bootToken = plainToken(tokensOf(bootProbe));
        if (!picked) {
            if (pageToken) {
                picked = pageToken;
                rule = '当前页面正文里只出现了这一个校区名';
            } else if (bootToken) {
                picked = bootToken;
                rule = '课表页 HTML 里只出现了这一个校区名';
            }
        }

        // c. 课表表头（第N节）括号里的时间与哪一套校区作息对得上
        var sectionTimes = sectionTimesOf(pageProbe);
        if (!sectionTimes.length) sectionTimes = sectionTimesOf(bootProbe);
        var timeDetail = '';
        if (!picked && sectionTimes.length) {
            var scores = [];
            for (i = 0; i < CAMPUS_TABLES.length; i++) {
                scores.push({
                    name: CAMPUS_TABLES[i].name,
                    hits: timeHits(CAMPUS_TABLES[i], sectionTimes)
                });
            }
            var best = scores[0];
            var second = scores[1];
            if (best.hits < second.hits) { var swap = best; best = second; second = swap; }
            var enough = best.hits >= 2 || (sectionTimes.length === 1 && best.hits === 1);
            if (enough && best.hits > second.hits) {
                picked = best.name;
                rule = '课表表头的节次时间（共 ' + sectionTimes.length + ' 节，与' +
                    best.name + '作息对上 ' + best.hits + ' 节）';
            } else {
                timeDetail = '课表表头读了 ' + sectionTimes.length + ' 个节次时间，但与两套校区作息都对不上' +
                    '（' + scores[0].name + ' 对上 ' + scores[0].hits + ' 节、' + scores[1].name +
                    ' 对上 ' + scores[1].hits + ' 节）';
            }
        }

        // d. 课程教室 / 地点里的校区名
        if (!picked && rooms.length) {
            var roomTokens = [];
            for (i = 0; i < rooms.length; i++) {
                var roomToken = campusTokenOf(rooms[i]);
                if (roomToken && roomTokens.indexOf(roomToken) < 0) roomTokens.push(roomToken);
            }
            if (roomTokens.length === 1) {
                picked = roomTokens[0];
                rule = '所有教室地点都写着同一个校区名';
            } else if (roomTokens.length > 1) {
                conflict = conflict || '教室地点里同时出现了两个校区名';
            }
        }

        var table = tableByName(picked);
        if (!picked) {
            // 判不出来：**不静默选一套**。默认用锦绣校区那张（它在页面上排第一、也是主校区名，
            // 上游的 timeSlot1 就是它），并在 warnings 里把「读了哪些线索、为什么没判出来」写清楚。
            table = CAMPUS_TABLES[0];
        }
        return {
            table: table,
            rule: rule,
            decided: !!picked,
            conflict: conflict,
            timeDetail: timeDetail,
            notes: notes,
            sectionTimes: sectionTimes
        };
    }

    // ---------- 课表 HTML：从 TaskActivity 里取安排 ----------
    var unitCount = 0;
    var unitCountSource = '';
    var unitMatch = /\bunitCount\s*=\s*(\d+)/.exec(courseHtml);
    if (unitMatch) {
        unitCount = parseInt(unitMatch[1], 10);
        unitCountSource = '页面里的 unitCount=' + unitCount;
    }
    if (!(unitCount > 0)) {
        unitCount = UNIT_FALLBACK;
        unitCountSource = '';
    }

    // 按 var teachers = [（没有就用 var actTeachers = [）把课表 HTML 切成一块块：
    // 上游就是用 html.split(/var\s+teachers\s*=/) 干这件事的，教师姓名块与它后面那条安排绑在一起。
    function teacherBlocks(html) {
        var starts = [];
        var re = /var\s+teachers\s*=\s*\[/g;
        var m;
        while ((m = re.exec(html)) !== null) starts.push(m.index);
        if (!starts.length) {
            re = /var\s+actTeachers\s*=\s*\[/g;
            while ((m = re.exec(html)) !== null) starts.push(m.index);
        }
        if (!starts.length) {
            return /new\s+TaskActivity\s*\(/.test(html) ? [html] : [];
        }
        var out = [];
        var i;
        for (i = 0; i < starts.length; i++) {
            var from = starts[i];
            var to = (i + 1 < starts.length) ? starts[i + 1] : html.length;
            out.push(html.substring(from, to));
        }
        return out;
    }

    // 这一块里的教师姓名。上游只把结果丢掉（teacherName 最后写的还是「未知教师」），这里把它用起来：
    // 优先 actTeachers（页面上真正参与这门课的教师），没有再看 teachers。
    function teachersIn(block) {
        var body = null;
        var m = /var\s+actTeachers\s*=\s*(\[[\s\S]*?\])\s*;/.exec(block);
        if (m) body = m[1];
        else {
            m = /var\s+teachers\s*=\s*(\[[\s\S]*?\])\s*;/.exec(block);
            if (m) body = m[1];
        }
        if (!body) return '';
        var names = [];
        var seen = {};
        var re = /[\{,]\s*name\s*:\s*(?:"([^"]*)"|'([^']*)')/g;
        var nm;
        while ((nm = re.exec(body)) !== null) {
            var name = tidy(nm[1] !== undefined && nm[1] !== null ? nm[1] : nm[2]);
            if (!name || seen[name]) continue;
            seen[name] = true;
            names.push(name);
        }
        return names.join(',');
    }

    // 从 '(' 起找配对的那个 ')'（引号里的括号不算），把 TaskActivity 的参数原文取出来。
    // 上游用的是 /new\s+TaskActivity\(([\s\S]*?)\);/ —— 课程名里带括号时会把参数截断，
    // 这里按括号配对取，稳健一些。
    function argsRawOf(source, openAt) {
        var depth = 0;
        var quote = '';
        var i;
        for (i = openAt; i < source.length; i++) {
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
                if (depth === 0) return { raw: source.substring(openAt + 1, i), end: i + 1 };
            }
        }
        return null;
    }

    // 上游的 powerSplit（顶层的逗号切参数，引号与括号里的逗号不算），ES5 化。
    // 返回**尚未去引号**的原文，好让调用方分辨「字面量」与「join(...) 表达式」。
    function splitArgs(raw) {
        var out = [];
        var current = '';
        var depth = 0;
        var inQuote = false;
        var quoteChar = '';
        var i;
        for (i = 0; i < raw.length; i++) {
            var ch = raw.charAt(i);
            if (inQuote) {
                current += ch;
                if (ch === '\\') { if (i + 1 < raw.length) { current += raw.charAt(i + 1); i++; } continue; }
                if (ch === quoteChar) inQuote = false;
                continue;
            }
            if (ch === '"' || ch === "'") { inQuote = true; quoteChar = ch; current += ch; continue; }
            if (ch === '(' || ch === '[' || ch === '{') depth++;
            if (ch === ')' || ch === ']' || ch === '}') depth--;
            if (ch === ',' && depth === 0) { out.push(current); current = ''; continue; }
            current += ch;
        }
        out.push(current);
        return out;
    }

    // 上游的 cleanArg：去空白、裸 null 变 null、剥掉首尾引号
    function cleanArg(raw) {
        var s = tidy(raw);
        if (s === 'null' || s === 'undefined') return '';
        if (s.length >= 2) {
            var head = s.charAt(0);
            var tail = s.charAt(s.length - 1);
            if ((head === '"' && tail === '"') || (head === "'" && tail === "'")) {
                return s.substring(1, s.length - 1);
            }
        }
        return s;
    }

    function looksLikeJoin(raw) {
        var s = tidy(raw);
        if (!s) return false;
        var head = s.charAt(0);
        if (head === '"' || head === "'") return false;   // 字面量，直接就是名字
        return s.indexOf('join(') >= 0;
    }

    function cleanName(raw) {
        var s = cleanArg(raw).replace(/<[^>]*>/g, '').replace(/&nbsp;|&#160;/gi, ' ');
        return tidy(s);
    }

    function cleanPosition(raw) {
        var s = cleanArg(raw).replace(/<[^>]*>/g, '').replace(/&nbsp;|&#160;/gi, ' ');
        // 上游的 .replace(/\(.*?\)/g, "") 会把括号里的内容当说明剥掉，但课程名的括号
        // （「教学楼A(新)」）不是。只在剥掉之后还剩东西时才剥 —— 剥空了就保留原样。
        var stripped = tidy(s.replace(/\(.*?\)/g, ''));
        if (stripped) return stripped;
        return tidy(s);
    }

    // 周次位图：下标 i 就是第 i 周，下标 0 是占位符（本批统一口径，见文件头 ④ 与 AUDIT.md）。
    var bitmapZeroOnes = 0;
    var weeksOverCap = 0;

    function weeksOfBitmap(bitmap) {
        var s = text(bitmap);
        var weeks = [];
        var seen = {};
        if (!s) return weeks;
        var i;
        for (i = 1; i < s.length && i <= MAX_WEEK; i++) {
            if (s.charAt(i) !== '1') continue;
            if (seen[i]) continue;
            seen[i] = true;
            weeks.push(i);
        }
        for (i = MAX_WEEK + 1; i < s.length; i++) {
            if (s.charAt(i) === '1') weeksOverCap++;
        }
        if (s.charAt(0) === '1') bitmapZeroOnes++;
        weeks.sort(function (a, b) { return a - b; });
        return weeks;
    }

    // index 寻址：上游只认 index = 星期 * unitCount + 节次。两种写法都认：
    //   带变量/常量乘子的  index = 5*unitCount+2; / index = 5*12+2;
    //   已算好的裸数字     index = 62;   ← 上游的正则会把它整条丢掉（见文件头 ⑧）
    var bareIndexes = 0;
    var badIndexes = 0;
    var noIndex = 0;
    var literalUnitMismatch = 0;

    function indexPlan(source, fallbackUnit) {
        var linear = [];
        var re = /index\s*=\s*(?:(\d+)\s*\*\s*(unitCount|(\d+))\s*\+\s*(\d+)|(\d+))\s*[;\)]/g;
        var m;
        while ((m = re.exec(source)) !== null) {
            var unit = fallbackUnit;
            var raw;
            if (m[1] !== undefined && m[1] !== null) {
                if (m[2] === 'unitCount') unit = fallbackUnit;
                else {
                    unit = parseInt(m[3], 10);
                    if (unit !== fallbackUnit) literalUnitMismatch++;
                }
                if (!(unit > 0)) continue;
                raw = parseInt(m[1], 10) * unit + parseInt(m[4], 10);
            } else {
                raw = parseInt(m[5], 10);
                bareIndexes++;
            }
            if (isNaN(raw) || raw < 0) { badIndexes++; continue; }
            linear.push(raw);
        }
        return linear;
    }

    function placeOf(linear, unit) {
        if (!(unit > 0)) return null;
        var day = Math.floor(linear / unit) + 1;
        var section = (linear % unit) + 1;
        if (day < 1 || day > 7 || section < 1 || section > MAX_PERIOD) return null;
        return { day: day, section: section };
    }

    // ---------- 解析每条安排 ----------
    var rawLessons = [];
    var badArgs = 0;
    var multiActivityBlocks = 0;
    var noWeeks = 0;

    var blocks = teacherBlocks(courseHtml);
    var bi;
    for (bi = 0; bi < blocks.length; bi++) {
        var block = blocks[bi];
        var teacher = teachersIn(block);
        var actRe = /new\s+TaskActivity\s*\(/g;
        var act;
        var activities = [];
        while ((act = actRe.exec(block)) !== null) {
            var openAt = actRe.lastIndex - 1;
            var captured = argsRawOf(block, openAt);
            if (!captured) { badArgs++; continue; }
            actRe.lastIndex = captured.end;
            activities.push(captured);
        }
        if (!activities.length) continue;
        if (activities.length > 1) multiActivityBlocks++;

        var ai;
        for (ai = 0; ai < activities.length; ai++) {
            var args = splitArgs(activities[ai].raw);
            if (args.length < 7) { badArgs++; continue; }
            var name = cleanName(args[3]);
            if (!name) { badArgs++; continue; }
            var position = cleanPosition(args[5]);
            var weeks = weeksOfBitmap(cleanArg(args[6]));
            // 没有周次的安排排不进课表：这里就把它算成一条解析失败的安排（计数进 warnings），
            // 不让它带着空周次走到合并那一步。
            if (!weeks.length) { noWeeks++; continue; }
            var teacherName = teacher;
            if (looksLikeJoin(args[1]) && !teacherName) {
                // 教师块在别处（上游也是到处找的），这里找不到就留空，不写「未知教师」
                teacherName = '';
            } else if (!looksLikeJoin(args[1])) {
                var literal = tidy(cleanArg(args[1]));
                if (literal) teacherName = literal;
            }
            var scopeFrom = activities[ai].end;
            var scopeTo = (ai + 1 < activities.length) ? activities[ai].start : block.length;
            // index 寻址要按住「它落在哪条 TaskActivity 上」分块：同一条安排的 index 段到
            // 下一条 activity = new TaskActivity 为止（页面里每条活动之间也可能只隔一个分号）。
            var scope = block.substring(scopeFrom, scopeTo);
            var nextActivityAt = scope.search(/activity\s*=\s*new\s+TaskActivity/);
            if (nextActivityAt >= 0) scope = scope.substring(0, nextActivityAt);
            var plan = indexPlan(scope, unitCount);
            if (!plan.length) { noIndex++; continue; }
            var pi;
            var placed = 0;
            for (pi = 0; pi < plan.length; pi++) {
                var place = placeOf(plan[pi], unitCount);
                if (!place) { badIndexes++; continue; }
                placed++;
                rawLessons.push({
                    name: name,
                    teacher: teacherName,
                    position: position,
                    day: place.day,
                    startSection: place.section,
                    endSection: place.section,
                    weeks: weeks
                });
            }
            if (!placed && plan.length) noIndex++;
        }
    }

    // ---------- 课程合并（上游 mergeContinuousLessons，原样移植） ----------
    // 按 (课名|教师|地点|星期) 分组，把每条安排按「第几周 → 上了哪几节」摊成一张矩阵，
    // 再把每一周里连续的节次并成块，最后按块把上过它的周次收在一起。
    var MERGE_WEEKS = MAX_WEEK + 1;

    function mergeContinuousLessons(lessons) {
        if (!lessons || !lessons.length) return [];
        var keys = [];
        var groups = {};
        var i;
        for (i = 0; i < lessons.length; i++) {
            var l = lessons[i];
            var key = l.name + SEP + l.teacher + SEP + l.position + SEP + l.day;
            if (!groups[key]) {
                var matrix = [];
                for (var w = 0; w < MERGE_WEEKS; w++) matrix.push({});
                groups[key] = {
                    name: l.name,
                    teacher: l.teacher,
                    position: l.position,
                    day: l.day,
                    weeksMatrix: matrix
                };
                keys.push(key);
            }
            var group = groups[key];
            // 同理不用 instanceof Array：跨全局对象的数组在 Rhino 里认不出来
            if (l.weeks && typeof l.weeks === 'object' && typeof l.weeks.length === 'number') {
                for (var wi = 0; wi < l.weeks.length; wi++) {
                    var week = l.weeks[wi];
                    if (!(week >= 0 && week < MERGE_WEEKS)) continue;
                    for (var s = l.startSection; s <= l.endSection; s++) {
                        group.weeksMatrix[week][s] = true;
                    }
                }
            }
        }

        var merged = [];
        var ki;
        for (ki = 0; ki < keys.length; ki++) {
            var g = groups[keys[ki]];
            var blockOrder = [];
            var blockMap = {};
            var w2;
            for (w2 = 0; w2 < MERGE_WEEKS; w2++) {
                var sections = [];
                for (var sec in g.weeksMatrix[w2]) {
                    if (!Object.prototype.hasOwnProperty.call(g.weeksMatrix[w2], sec)) continue;
                    sections.push(parseInt(sec, 10));
                }
                if (!sections.length) continue;
                sections.sort(function (a, b) { return a - b; });
                var start = sections[0];
                var prev = sections[0];
                var si;
                for (si = 1; si < sections.length; si++) {
                    var curr = sections[si];
                    if (curr === prev + 1) { prev = curr; continue; }
                    var blockKey = start + '-' + prev;
                    if (!blockMap[blockKey]) { blockMap[blockKey] = []; blockOrder.push(blockKey); }
                    blockMap[blockKey].push(w2);
                    start = curr;
                    prev = curr;
                }
                var lastKey = start + '-' + prev;
                if (!blockMap[lastKey]) { blockMap[lastKey] = []; blockOrder.push(lastKey); }
                blockMap[lastKey].push(w2);
            }
            var bo;
            for (bo = 0; bo < blockOrder.length; bo++) {
                var parts = blockOrder[bo].split('-');
                merged.push({
                    name: g.name,
                    teacher: g.teacher,
                    position: g.position,
                    day: g.day,
                    startSection: parseInt(parts[0], 10),
                    endSection: parseInt(parts[1], 10),
                    weeks: blockMap[blockOrder[bo]]
                });
            }
        }

        merged.sort(function (a, b) {
            return cmpNum(a.day, b.day) || cmpNum(a.startSection, b.startSection) ||
                cmpNum(a.endSection, b.endSection) || cmpStr(a.name, b.name) ||
                cmpStr(a.teacher, b.teacher) || cmpStr(a.position, b.position) ||
                cmpStr(a.weeks.join(','), b.weeks.join(','));
        });
        return merged;
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
                run.weekType = (run.start % 2 === 1) ? 'ODD' : 'EVEN';
            }
            runs.push(run);
            i = j + 1;
        }
        return runs;
    }

    var merged = mergeContinuousLessons(rawLessons);

    var order = [];
    var byCourse = {};
    var mi;
    for (mi = 0; mi < merged.length; mi++) {
        var item = merged[mi];
        var courseKey = item.name + SEP + item.teacher;
        if (!byCourse[courseKey]) {
            byCourse[courseKey] = {
                name: item.name,
                teacher: item.teacher || null,
                note: null,
                blocks: [],
                seen: {}
            };
            order.push(courseKey);
        }
    }

    var maxWeek = 0;
    var maxPeriod = 0;
    for (mi = 0; mi < merged.length; mi++) {
        var mItem = merged[mi];
        var course = byCourse[mItem.name + SEP + mItem.teacher];
        if (!course) continue;
        var runs = runsOf(mItem.weeks);
        var ri;
        for (ri = 0; ri < runs.length; ri++) {
            var run = runs[ri];
            var blockKey = mItem.day + '|' + mItem.startSection + '|' + mItem.endSection + '|' +
                run.start + '|' + run.end + '|' + run.weekType + '|' + mItem.position;
            if (course.seen[blockKey]) continue;
            course.seen[blockKey] = true;
            if (run.end > maxWeek) maxWeek = run.end;
            if (mItem.endSection > maxPeriod) maxPeriod = mItem.endSection;
            course.blocks.push({
                dayOfWeek: mItem.day,
                startPeriod: mItem.startSection,
                endPeriod: mItem.endSection,
                startWeek: run.start,
                endWeek: run.end,
                weekType: run.weekType,
                location: mItem.position || null
            });
        }
    }

    // 课程顺序：按「第一次出场」排。上游 mergeContinuousLessons 里那条按（星期、起始节、课名）
    // 排序只影响它自己的输出顺序，我们这里重排一次 —— 同一门课可能被两条上游安排各自贡献一块
    // （例如两条 index 落在同一个块里），谁先出场就按谁排，结果与引擎无关。
    order.sort(function (a, b) {
        var ca = byCourse[a];
        var cb = byCourse[b];
        var da = ca.blocks.length ? ca.blocks[0].dayOfWeek : 8;
        var db = cb.blocks.length ? cb.blocks[0].dayOfWeek : 8;
        var pa = ca.blocks.length ? ca.blocks[0].startPeriod : 0;
        var pb = cb.blocks.length ? cb.blocks[0].startPeriod : 0;
        return cmpNum(da, db) || cmpNum(pa, pb) || cmpStr(ca.name, cb.name) ||
            cmpStr(ca.teacher || '', cb.teacher || '');
    });

    var courses = [];
    var oi;
    for (oi = 0; oi < order.length; oi++) {
        var built = byCourse[order[oi]];
        built.blocks.sort(function (a, b) {
            return cmpNum(a.dayOfWeek, b.dayOfWeek) || cmpNum(a.startPeriod, b.startPeriod) ||
                cmpNum(a.endPeriod, b.endPeriod) || cmpNum(a.startWeek, b.startWeek) ||
                cmpNum(a.endWeek, b.endWeek) || cmpStr(a.weekType, b.weekType) ||
                cmpStr(a.location || '', b.location || '');
        });
        courses.push({ name: built.name, teacher: built.teacher, note: built.note, blocks: built.blocks });
    }

    if (!courses.length) {
        // 两种失败要分清楚：教务给了一张空课表，和「给了课但我们一条都没读懂」。
        // 后者八成是教务改了 TaskActivity 的格式，报「可能还没排课」会把用户和我们一起带偏。
        if (courseHtml && /new\s+TaskActivity\s*\(/.test(courseHtml)) {
            throw new Error(
                '教务系统返回的课表页面里有课程数据，但没有一条能解析成课程（读不出参数的安排 ' + badArgs +
                ' 条、找不到 index 的 ' + noIndex + ' 条、index 越界的 ' + badIndexes +
                ' 条）：多半是教务系统改了课表页面的格式，请把这条消息反馈给我们'
            );
        }
        if (courseHtml) {
            throw new Error(
                '这个学期的课表页面里没有任何课程数据：可能还没排课（假期里常见），' +
                '也可能登录状态已失效。请重新登录、打开「课表查询」确认能看到课表后再点「提取课表」'
            );
        }
        throw new Error('没有取到课表数据：请确认已登录教务系统，并在教务页面里点「提取课表」');
    }

    // ---------- 学期名 / 开学日 / 总周数 ----------
    var todayIso = isoOfAny(data.today) || localTodayIso();
    var startIso = isoOfAny(semester.startDate);
    var endIso = isoOfAny(semester.endDate);

    var calendarWeeks = 0;
    if (startIso && endIso) {
        var spanDays = dayNumber(endIso) - dayNumber(startIso);
        if (spanDays >= 0) calendarWeeks = Math.floor(spanDays / 7) + 1;
    }
    if (calendarWeeks > MAX_WEEK) calendarWeeks = MAX_WEEK;

    var firstDay;
    var startRule;
    if (startIso) {
        // 手册 §4.3：开学日回到「第 1 周的第一天」。教务给的是学期起始日，
        // 它未必是周一，所以回退到所在周的周一（缺省 firstDayOfWeek = 1）。
        firstDay = mondayOfIso(startIso);
        startRule = '教务学期日历的学期起始日 ' + startIso + ' 所在周的周一';
    } else {
        firstDay = mondayOfIso(todayIso);
        startRule = '教务没有给出学期起止日期，按今天的周一（' + todayIso + '）推算';
    }

    var weeksSource;
    var totalWeeks;
    if (calendarWeeks > 0) {
        weeksSource = '教务学期日历（' + startIso + ' 到 ' + endIso + '，共 ' + calendarWeeks + ' 周）';
        totalWeeks = calendarWeeks;
    } else {
        weeksSource = '适配器内置的 ' + FALLBACK_TOTAL_WEEKS + ' 周（教务学期日历没有给出可用的起止日期）';
        totalWeeks = FALLBACK_TOTAL_WEEKS;
    }
    var raisedBySchedule = false;
    var clamped = false;
    if (maxWeek > totalWeeks) { totalWeeks = maxWeek; raisedBySchedule = true; }
    if (totalWeeks > MAX_WEEK) { totalWeeks = MAX_WEEK; clamped = true; }
    if (totalWeeks < 1) totalWeeks = FALLBACK_TOTAL_WEEKS;

    function termLabelOf(raw) {
        var s = tidy(raw);
        if (!s) return '';
        if (s.indexOf('一') >= 0) return '第一学期';
        if (s.indexOf('二') >= 0) return '第二学期';
        if (s.indexOf('三') >= 0) return '第三学期';
        if (/学年/.test(s) && /学期/.test(s)) return s;
        if (s === '1') return '第一学期';
        if (s === '2') return '第二学期';
        if (s === '3') return '第三学期';
        return s.indexOf('学期') >= 0 ? s : s + '学期';
    }

    function guessYearLabel() {
        var year = parseInt(todayIso.substring(0, 4), 10);
        var month = parseInt(todayIso.substring(5, 7), 10);
        if (isNaN(year) || isNaN(month)) return '';
        if (month >= 8) return year + '-' + (year + 1);
        return (year - 1) + '-' + year;
    }

    var yearLabel = tidy(semester.schoolYear);
    var shortLabel = termLabelOf(semester.termName);
    if (!shortLabel) shortLabel = termLabelOf(semester.label);
    var termName;
    if (/^\d{4}\s*-\s*\d{2,4}$/.test(yearLabel) && shortLabel.indexOf('学期') >= 0) {
        // 教务给了学年 + 学期（手册 §4.7：教务给的就用教务的）
        termName = yearLabel.replace(/\s+/g, '') + '学年' + shortLabel;
    } else if (/学年/.test(tidy(semester.label)) && /学期/.test(tidy(semester.label))) {
        termName = tidy(semester.label);
    } else {
        var guessed = guessYearLabel() || yearLabel;
        termName = SCHOOL + ' ' + guessed + '学年' + (shortLabel || '第一学期');
    }

    // ---------- 作息时间 ----------
    var rooms = [];
    var li;
    for (li = 0; li < rawLessons.length; li++) {
        var roomName = rawLessons[li].position;
        if (roomName && rooms.indexOf(roomName) < 0) rooms.push(roomName);
    }
    var campus = campusPlan(rooms);

    var periodTimes = [];
    var badSlots = 0;
    var si2;
    for (si2 = 0; si2 < campus.table.slots.length; si2++) {
        var slot = campus.table.slots[si2];
        var slotStart = timeOf(slot.start);
        var slotEnd = timeOf(slot.end);
        if (!slotStart || !slotEnd || minutesOf(slotStart) >= minutesOf(slotEnd)) { badSlots++; continue; }
        periodTimes.push({ periodIndex: slot.periodIndex, start: slotStart, end: slotEnd });
    }
    var tableLength = periodTimes.length;
    var extendedTo = 0;
    var uncoveredFrom = 0;
    for (var p = tableLength + 1; p <= maxPeriod; p++) {
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
        var line = text(message);
        if (line.length > MAX_WARNING_CHARS) {
            // 最后放一句固定的「被截断了」标记：warnings 是整个载荷里离用户最近的一手线索，
            // 悄悄把尾巴砍掉会让用户拿到半句业务说明却不知道它不完整。
            var cut = MAX_WARNING_CHARS - 5;
            line = line.substring(0, cut) + '（后略）';
        }
        allWarnings.push(line);
    }

    warn(
        '只导入了教务系统当前选中的学期（' + termName +
        '）；要导入别的学期，请在教务页面里切到那个学期再点「提取课表」'
    );

    if (campus.decided) {
        warn(
            '作息时间用的是' + campus.table.name + '的 ' + tableLength + ' 节作息表（依据：' + campus.rule +
            '）；这张表是适配器内置的、没有向教务核对过，如与学校实际作息不符请在学期管理里改'
        );
    } else {
        var why = [];
        if (campus.conflict) why.push(campus.conflict);
        why.push('页面上没有已勾选的校区按钮');
        why.push('页面与课表页 HTML 里没有只出现一个的校区名');
        if (campus.timeDetail) why.push(campus.timeDetail);
        why.push('课程地点里也没有校区名');
        warn(
            '没能判断出你在哪个校区（' + why.join('；') + '），作息时间暂用' + campus.table.name +
            '的 ' + tableLength + ' 节作息表（第 1 节 ' + periodTimes[0].start + '-' + periodTimes[0].end +
            '），请在学期管理里核对或改成实际校区的作息'
        );
    }

    if (startIso) {
        warn('开学日期取自教务的学期日历（第 1 周从 ' + firstDay + ' 开始，' + startRule + '），请在学期管理里核对');
    } else {
        warn('教务没有给出学期起止日期，第 1 周按「' + startRule + '」推定为 ' + firstDay + '，请在学期管理里核对成学校实际开学日');
    }

    // 学期是自动挑的（上游是弹窗让用户选），挑法也如实说：教务自己标的当前学期 / 按今天推的 /
    // 列表里最后一个。后两种可能挑错学期，用户看到这句才有机会发现。
    if (semester.pickedBy === 'today') {
        warn(
            '教务的学期日历没有标出当前学期，已按今天的日期（' + todayIso + '）在学期列表里挑了「' + termName +
            '」；如果不是你要的学期，请在教务页面里切到那个学期再点「提取课表」'
        );
    } else if (semester.pickedBy === 'last') {
        warn(
            '教务的学期日历既没有标出当前学期、也没有与今天对得上的学期，已取列表里的最后一个「' + termName +
            '」（列表共 ' + text(semester.count) + ' 个学期）；如果不是你要的学期，请在教务页面里切到那个学期再点「提取课表」'
        );
    }

    if (raisedBySchedule) {
        warn(
            '学期总周数用的是' + weeksSource + '，但课表里有第 ' + maxWeek + ' 周的课，已按 ' + totalWeeks +
            ' 周导入（否则那几周的课放不下），如与实际不符可在学期管理里改'
        );
    } else {
        warn('学期总周数用的是' + weeksSource + (clamped ? '，已按载荷上限 ' + MAX_WEEK + ' 周截断' : '') +
            '，如与实际不符可在学期管理里改');
    }

    if (!unitCountSource) {
        warn(
            '课表页面里没有读到 unitCount（每天几节），已按缺省 ' + UNIT_FALLBACK +
            ' 节反推每门课的星期与节次（这是上游脚本写死的缺省值），请核对节次是否正确'
        );
    }

    if (bitmapZeroOnes > 0) {
        warn(
            '有 ' + bitmapZeroOnes + ' 条课程的周次位图第 0 位是 1。树维 EAMS 的位图第 0 位是占位符、' +
            '不是第 0 周（本适配器按同族统一口径：下标 i 就是第 i 周），已忽略这一位，请在导入预览里核对周次'
        );
    }
    if (weeksOverCap > 0) {
        warn('有 ' + weeksOverCap + ' 个周次超出 1-' + MAX_WEEK + ' 周的上限，已丢弃（教务给出的周次不正常）');
    }

    var skipped = badArgs + noIndex + badIndexes + noWeeks;
    if (skipped > 0) {
        var reasons = [];
        if (badArgs) reasons.push('读不出 TaskActivity 参数 ' + badArgs + ' 条');
        if (noIndex) reasons.push('找不到 index 寻址 ' + noIndex + ' 条');
        if (badIndexes) reasons.push('index 反推不出合法的星期/节次 ' + badIndexes + ' 条');
        if (noWeeks) reasons.push('周次位图里没有任何一位是 1（没给出周次）' + noWeeks + ' 条');
        warn('有 ' + skipped + ' 条课表安排没能解析（' + reasons.join('、') + '），已跳过：如发现少课请反馈');
    }
    if (bareIndexes > 0) {
        warn(
            '有 ' + bareIndexes + ' 条安排用的是已经算好的 index（上游的同族脚本认不出这种写法、会静默丢掉这些课）：' +
            '已按页面上的 unitCount=' + unitCount + ' 反推星期与节次，请核对'
        );
    }
    if (literalUnitMismatch > 0) {
        warn(
            '有 ' + literalUnitMismatch + ' 条安排的 index 表达式里写的每日节数与页面上的 unitCount=' +
            unitCount + ' 不一致，已按页面上的 unitCount 反推，请核对'
        );
    }
    if (multiActivityBlocks > 0) {
        warn(
            '有 ' + multiActivityBlocks + ' 处教师姓名块后面跟着不止一条课程安排，这些安排共用同一组教师姓名，' +
            '教师可能不准，请核对'
        );
    }
    if (badSlots > 0) {
        warn('适配器内置的' + campus.table.name + '作息表里有 ' + badSlots + ' 节的时间不合法，已丢弃这些节次，请反馈');
    }
    if (extendedTo || uncoveredFrom) {
        var notes = [];
        if (extendedTo) {
            notes.push(extendedTo > tableLength + 1
                ? ('第 ' + (tableLength + 1) + '-' + extendedTo + ' 节按空课内建节次表补了时间')
                : ('第 ' + extendedTo + ' 节按空课内建节次表补了时间'));
        }
        if (uncoveredFrom) notes.push('第 ' + uncoveredFrom + ' 节及之后没有可用的作息时间');
        warn(
            '课表里用到第 ' + maxPeriod + ' 节，而' + campus.table.name + '作息表只到第 ' + tableLength +
            ' 节：' + notes.join('；') + '，请核对'
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
