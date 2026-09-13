(function () {
    // 东北大学教务适配器（金智教育 jwapp / WIS 平台）—— 原始数据 → 空课课表载荷
    //
    // 移植自 shiguang_warehouse 的 NEU/neu.js（MIT，上游作者 Vera-zero）
    //   https://github.com/XingHeYuZhuan/shiguang_warehouse
    // 上游快照：main @ e62554a（2026-09-12）
    //
    // 这一段是纯转换（不碰 DOM、不发请求、不弹窗），所以能在 CI 里用 Rhino 跑回归。
    //
    // 移植改动（与上游的语义差异，逐条列在这里）：
    //   ① 周次：上游发显式的 weeks 数组，这里按空课载荷切成极大段 —— 步长 1 是每周（ALL），
    //      步长 2 是单/双周（ODD/EVEN），落单的一周单独成段。见移植手册 §4.1。
    //   ② 上游的 parseWeeksString 只认「1-8周」和「2-6周(双)」两种写法，而这正是本批
    //      检查表第 1 条点名的那类坑：
    //        - 「1,3,5周」「1-3,5-9周」这种逗号串里除最后一段外都**不带「周」字**，
    //          上游的正则匹配不上，前几段被**静默丢掉**（少了半个月的课，不报错）；
    //        - 「(单)1-16周」（标记在「周」前）、「1-16(单周)」（标记在括号里）上游认不出，
    //          整条排课的周次全丢；
    //        - 「3周(双)」这种单双标记与明写周次矛盾的，上游直接丢周。
    //      这里统一解析（四种写法 + 中文逗号/顿号/分号/斜杠分隔 + 「第」「至」等杂字），
    //      认不出的片段进 warnings 并跳过该条排课，不静默丢。
    //   ③ 周次来源：上游只认 titleWeekTeacherClassroomDetail 里的文本。**同一个接口**
    //      （kbapp/api/wdkbcx/getMyScheduleDetail.do）在上游 CAPU 的适配器里用的是
    //      item.week **位串**（'1' 表示该周上课），XJZFU 那一支也是。所以两者都认：
    //      先解析文本片段，片段里拿不到周次时退回 item.week 位串。
    //   ④ 教师 / 教室：上游把 "1-16周 教1-101" 这种两段文本的第二段当成教师，
    //      于是教师栏出现「教1-101」。两段且第二段含数字或楼/室/馆/场/区时判为教室
    //      （中文教师姓名不会有这些字符），避免编出一个不存在的教师。
    //      教师拿不到就留空（null），不写「未知」「待定」—— 详见移植手册 §4.7。
    //   ⑤ 校区 / 作息表：上游让用户手选南湖/浑南，再套一份**写死的**作息表。这里改成用
    //      教务自己的节次接口给的时间；拿不到、或与课表用的节次对不上，就不写 periodTimes
    //      （应用会补默认节次表）并进 warnings —— 绝不写死一份可能属于另一个校区的作息。
    //   ⑥ 开学日：上游根本没设 semesterStartDate。这里优先用教务的学期周次接口，
    //      拿不到就按「最近的周一」推算并写进 warnings（手册 §4.2）。
    //      上游 config 里的 firstDayOfWeek = 7（周日）**没有采用**：空课的载荷靠
    //      firstDay 的星期几决定列对齐，而 dayOfWeek 是**绝对**星期（1 = 周一，
    //      金智平台口径；东北大学自己的研究生适配器 neuyjs.js 用的是同义的 XQ 字段）。
    //      若把 firstDay 退到周日，整学期的课会整体偏一天。
    //   ⑦ 考试：上游的「导入考试时间」是可选交互步骤（它自己标注为测试功能）。空课载荷
    //      里没有考试这个概念，要放进去只能编一门叫「课程名_考试_日期」的假课，
    //      所以没有移植 —— 详见 AUDIT.md。
    var data = JSON.parse(typeof __ncInput !== 'undefined' ? __ncInput : '{}');
    var rows = Object.prototype.toString.call(data.rows) === '[object Array]' ? data.rows : [];
    var sections = Object.prototype.toString.call(data.sections) === '[object Array]' ? data.sections : [];
    var term = data.term || {};

    // 载荷校验要求 totalWeeks 落在 1..30（规范 §4）
    var MAX_WEEKS = 30;
    var FALLBACK_WEEKS = 20;
    var MAX_WARNINGS = 20;
    var MAX_WARNING_CHARS = 200;

    // 复合键的分隔符：用 fromCharCode 拼出来，源码里不出现转义序列 ——
    // 反斜杠 + u0000 那种写法会被某些编辑器落成**真的 NUL 字节**，让整个文件变成二进制
    // （移植手册 §3 第 2 步，同平台的 dlutci 中过招）。运行时值不变。
    var KEY_SEP = String.fromCharCode(0);

    function text(value) {
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

    function isArray(value) {
        return Object.prototype.toString.call(value) === '[object Array]';
    }

    // 校验：任何写进 periodTimes 的时间必须是 HH:mm 且落在 00:00–23:59。
    // 24:00 / 85:45 这类会让**整个载荷被拒**（不是跳过一节），所以宁可不要这一节。
    function hhmm(value) {
        var match = /^([0-9]{1,2}):([0-9]{2})(?::[0-9]{2})?$/.exec(text(value));
        if (!match) return null;
        var h = parseInt(match[1], 10);
        var mi = parseInt(match[2], 10);
        if (h > 23 || mi > 59) return null;
        return pad2(h) + ':' + pad2(mi);
    }

    // 'yyyy-MM-dd' → 该日期所在周的周一（含当天）。移植手册 §4.3：我们数周次前先把
    // 开学日回退到 firstDayOfWeek（缺省 1 = 周一）那天，直接拿开学日当第 1 周会把
    // 整学期的课偏几天。
    function mondayOnOrBefore(iso) {
        var match = /^(\d{4})-(\d{2})-(\d{2})/.exec(String(iso));
        if (!match) return null;
        var year = parseInt(match[1], 10);
        var month = parseInt(match[2], 10);
        var day = parseInt(match[3], 10);
        var date = new Date(Date.UTC(year, month - 1, day));
        if (isNaN(date.getTime())) return null;
        // 非法日期（如 2026-02-31）会被 Date 归一化成下个月，拒绝而不是悄悄顺延
        if (date.getUTCFullYear() !== year || date.getUTCMonth() !== month - 1 || date.getUTCDate() !== day) {
            return null;
        }
        var offset = (date.getUTCDay() + 6) % 7;
        var monday = new Date(date.getTime() - offset * 86400000);
        return monday.getUTCFullYear() + '-' + pad2(monday.getUTCMonth() + 1) + '-' + pad2(monday.getUTCDate());
    }

    // extract 没给任何开学日时的最后兜底（本机当前周的周一）。
    // 正常路径下 extract 给的是教务的开学日或同口径的推算值，所以这条只在
    // 输入被手工改坏时才会走到 —— 它的作用是保证载荷永远合法（firstDay 是必填项）。
    function currentMondayIso() {
        var now = new Date();
        var offset = (now.getDay() + 6) % 7;
        var monday = new Date(now.getTime() - offset * 86400000);
        return monday.getFullYear() + '-' + pad2(monday.getMonth() + 1) + '-' + pad2(monday.getDate());
    }

    // item.week 是「第 i 位 = 第 i+1 周」的位串（金智 jwapp 的学生课表标准编码之一，
    // 上游 CAPU / XJZFU 都用它）。不是位串就返回空数组。
    function weeksFromBits(bits) {
        var weeks = [];
        if (!/^[01]+$/.test(bits)) return weeks;
        for (var i = 0; i < bits.length; i++) {
            if (bits.charAt(i) === '1') weeks.push(i + 1);
        }
        return weeks;
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

    // 单个周次片段 → 周次数组。四种写法都要认：
    //   「1-16周(单)」（标记在「周」后）、「(单)1-16周」（标记在前）、
    //   「1-16(单周)」（标记在括号里）、「1-3,5-9周」（混排，逗号在上一层切）
    // 外加「1-16周」「6周」「第1-8周」「1至16周」这些同义写法。
    // **括号里的纯数字/数字区间不是周次**（检查表第 2 条）：那是教学班序号，
    // 必须在拼周次数字**之前整组删掉** —— 只删括号符号会把它粘进周次数字里
    // （「(1)1-16周」粘成 11-16、「1-16周(1)」粘成 1-161），拼完再删更救不回来。
    // 认不出的片段记进 notes.bad（**不静默丢**），返回空数组。
    function weeksFromSegment(seg, notes) {
        var raw = text(seg);
        if (!raw) return [];
        var s = raw.replace(/第/g, '');
        var odd = s.indexOf('单') >= 0;
        var even = s.indexOf('双') >= 0;
        var withoutIndex = s.replace(/[（(][ ]*[0-9]+([ ]*[-–—~至][ ]*[0-9]+)?[ ]*[）)]/g, ' ');
        var body = withoutIndex.replace(/[（）()]/g, '')
            .replace(/周/g, '')
            .replace(/[单双]/g, '')
            .replace(/[–—~至]/g, '-')
            .replace(/\s/g, '');
        if (!/^[0-9-]+$/.test(body)) {
            notes.bad.push(raw);
            return [];
        }
        var range = /^([0-9]+)-([0-9]+)$/.exec(body);
        if (range) {
            var from = parseInt(range[1], 10);
            var to = parseInt(range[2], 10);
            var out = [];
            for (var i = from; i <= to; i++) {
                if (odd && i % 2 === 0) continue;
                if (even && i % 2 === 1) continue;
                out.push(i);
            }
            // 「1-1周(双)」这种：区间合法但被单双标记筛空了，记一笔
            if (!out.length) notes.bad.push(raw);
            return out;
        }
        var single = /^([0-9]+)$/.exec(body);
        if (!single) {
            notes.bad.push(raw);
            return [];
        }
        var n = parseInt(single[1], 10);
        // 「3周(双)」这类单双标记与明写周次矛盾的：周次是明写的，保留它（丢掉等于
        // 凭空少上一节课），但要留痕。
        if ((odd && n % 2 === 0) || (even && n % 2 === 1)) notes.conflict.push(raw);
        return [n];
    }

    function weeksFromText(source, notes) {
        var parts = text(source).split(/[,，、;；/]/);
        var weeks = [];
        for (var i = 0; i < parts.length; i++) {
            weeks = weeks.concat(weeksFromSegment(parts[i], notes));
        }
        return uniqueSorted(weeks);
    }

    // 周次集合（升序去重）→ 极大段。步长 1 视作每周，步长 2 视作单/双周，落单的一周
    // 单独成段（见移植手册 §4.1 的表）。一个片段因此可能产出多个 block。
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

    // 学期编号形如 2026-2027-1 → 「2026-2027学年第一学期」。
    // 教务给了学期名（kb/xnxq 的 itemName）就用教务的 —— 别拿适配器名当学期名。
    function termNameFrom(code) {
        var match = /^(\d{4})-(\d{4})-(\d{1,2})$/.exec(String(code));
        if (!match) return '';
        var index = parseInt(match[3], 10);
        var label = '第' + index + '学期';
        if (index === 1) label = '第一学期';
        else if (index === 2) label = '第二学期';
        else if (index === 3) label = '第三学期';
        return match[1] + '-' + match[2] + '学年' + label;
    }

    // 排课行里的上课安排文本。上游读 titleWeekTeacherClassroomDetail（字符串数组）；
    // 同一个接口在上游 CAPU 那边叫 cellWeekTeacherClassroomDetail（{ text } 对象数组）。
    // 两种都认，先做一次 HTML 标签剥离（金智常把教师姓名包在 <a> 里）。
    function detailTexts(row) {
        var sources = [row.titleWeekTeacherClassroomDetail, row.cellWeekTeacherClassroomDetail];
        for (var s = 0; s < sources.length; s++) {
            var list = sources[s];
            if (!isArray(list)) continue;
            var out = [];
            for (var i = 0; i < list.length; i++) {
                var item = list[i];
                var raw = '';
                if (typeof item === 'string') raw = item;
                else if (item && typeof item.text === 'string') raw = item.text;
                var clean = text(raw.replace(/<[^>]*>/g, ' ').replace(/&nbsp;/g, ' '));
                if (clean) out.push(clean);
            }
            if (out.length) return out;
        }
        return [];
    }

    function rowLocation(row) {
        return text(row.placeName) || text(row.campusName) || null;
    }

    function clip(value) {
        var s = String(value);
        return s.length > MAX_WARNING_CHARS ? s.substring(0, MAX_WARNING_CHARS - 3) + '...' : s;
    }

    function sampleOf(list, limit) {
        var shown = [];
        for (var i = 0; i < list.length && i < limit; i++) shown.push(list[i]);
        var s = shown.join('、');
        if (list.length > limit) s += ' 等 ' + list.length + ' 处';
        return s;
    }

    var warnings = [];
    var notes = { bad: [], conflict: [] };

    var order = [];
    var byCourse = {};
    var maxWeek = 0;
    var maxBits = 0;
    var skipped = 0;
    var droppedWeeks = 0;
    var maxEndPeriod = 0;
    var timeSamples = [];

    // 超出 30 周（载荷上限）的周次丢在这里，丢了多少条会进 warnings
    function clampWeeks(weeks) {
        var kept = [];
        for (var i = 0; i < weeks.length; i++) {
            if (weeks[i] <= MAX_WEEKS) kept.push(weeks[i]);
            else droppedWeeks++;
        }
        return kept;
    }

    for (var i = 0; i < rows.length; i++) {
        var row = rows[i] || {};
        var name = text(row.courseName);
        var day = intOf(row.dayOfWeek);
        var startPeriod = intOf(row.beginSection);
        var endPeriod = intOf(row.endSection);
        var bits = text(row.week);
        var bitWeeks = weeksFromBits(bits);
        var details = detailTexts(row);

        // 位串长度就是教务眼里的学期周数（同平台口径），只在它确实是位串时才算
        if (bitWeeks.length && bits.length > maxBits) maxBits = bits.length;

        // 完全空白的占位行（课名 / 星期 / 安排 / 位串都没有）不是「被丢掉的课」
        if (!name && !day && !details.length && !bitWeeks.length) continue;

        var keptBits = clampWeeks(bitWeeks);
        // 没有上课安排文本时合成一段空的：周次走位串，教师/教室走行级字段
        var segments = details.length ? details : [''];

        for (var s = 0; s < segments.length; s++) {
            var tokens = segments[s] ? segments[s].split(/\s+/) : [];
            var localNotes = { bad: [], conflict: [] };
            var weeks = clampWeeks(weeksFromText(tokens.length ? tokens[0] : '', localNotes));
            // 片段文本里没有周次信息 → 退回行级位串（同平台 CAPU 的口径）
            if (!weeks.length) weeks = keptBits;
            if (!weeks.length) {
                skipped++;
                notes.bad = notes.bad.concat(localNotes.bad);
                continue;
            }
            notes.conflict = notes.conflict.concat(localNotes.conflict);

            // 文本形状：「周次 教师 教室」（上游口径）。只有两段且第二段像教室时，
            // 判成教室而不是教师（中文教师姓名不含数字与楼/室/馆/场/区）。
            var teacher = null;
            var location = null;
            if (tokens.length >= 2) {
                if (tokens.length === 2 && /[0-9楼室馆场区]/.test(tokens[1])) {
                    location = tokens[1];
                } else {
                    teacher = tokens[1];
                    if (tokens.length > 2) location = tokens.slice(2).join(' ');
                }
            }
            if (!location) location = rowLocation(row);

            // 课名是载荷的必填项（规范 §4）：空课名的记录必须挡在这里 ——
            // 放进去会让**整个载荷**被校验拒绝（不是跳过这一门）。
            if (!name || !(day >= 1 && day <= 7) || !(startPeriod >= 1) || !(endPeriod >= startPeriod)) {
                skipped++;
                continue;
            }
            if (endPeriod > maxEndPeriod) maxEndPeriod = endPeriod;

            var beginTime = hhmm(row.beginTime);
            if (beginTime) timeSamples.push({ period: startPeriod, time: beginTime });

            var key = name + KEY_SEP + (teacher || '');
            if (!byCourse[key]) {
                byCourse[key] = { name: name, teacher: teacher, note: null, blocks: [] };
                order.push(key);
            }

            var runs = runsOf(weeks);
            for (var r = 0; r < runs.length; r++) {
                var run = runs[r];
                if (run.end > maxWeek) maxWeek = run.end;
                byCourse[key].blocks.push({
                    dayOfWeek: day,
                    startPeriod: startPeriod,
                    endPeriod: endPeriod,
                    startWeek: run.start,
                    endWeek: run.end,
                    weekType: run.weekType,
                    location: location
                });
            }
        }
    }

    var termCode = text(term.code);
    if (order.length === 0) {
        // 有课但全被周次上限挡掉时，别把原因说成「教务改了格式」
        var hint = droppedWeeks > 0
            ? '：课表里的周次都超过了 ' + MAX_WEEKS + ' 周（课表数据的上限），放不进空课'
            : '：可能是还没排课，或教务系统改了课表的数据格式。请在教务页面里确认课表已经显示出来再重试';
        throw new Error('没解析到任何课程' + (termCode ? '（学期编号 ' + termCode + '）' : '') + hint);
    }

    // ---- 作息时间 ------------------------------------------------------------
    // 节次编号取自教务节次表的名称（「第1节」→ 1），字段缺一个就整条丢掉。
    function periodTimesFrom(list) {
        var out = [];
        var seen = {};
        var bad = 0;
        for (var i = 0; i < list.length; i++) {
            var item = list[i] || {};
            var label = text(item.name);
            var digits = /([0-9]+)/.exec(label);
            var index = digits ? parseInt(digits[1], 10) : intOf(item.serialNumber);
            var start = hhmm(item.startTime);
            var end = hhmm(item.endTime);
            if (!(index >= 1) || index > MAX_WEEKS || !start || !end || !(start < end) || seen[index]) {
                if (label || text(item.startTime)) bad++;
                continue;
            }
            seen[index] = true;
            out.push({ periodIndex: index, start: start, end: end });
        }
        out.sort(function (a, b) { return a.periodIndex - b.periodIndex; });
        return { times: out, bad: bad };
    }

    var table = periodTimesFrom(sections);
    var periodTimes = table.times;
    var periodNote = null;
    var k;

    if (!periodTimes.length) {
        periodNote = '作息时间未能从教务获取，节次时间将使用空课的默认值，请在学期设置里核对';
    } else if (maxEndPeriod > periodTimes[periodTimes.length - 1].periodIndex) {
        // 作息表覆盖不到课表用到的节次 —— 很可能是两套编号（同平台 CAPU 就踩过：
        // 它的 beginSection 和节次表编号对不上，得按 beginTime 反查），整套都不可信
        periodNote = '教务的作息表只到第 ' + periodTimes[periodTimes.length - 1].periodIndex +
            ' 节，课表里却用到第 ' + maxEndPeriod + ' 节，已改用空课的默认节次时间，请在学期设置里核对';
        periodTimes = [];
    } else {
        // 行里带 beginTime 的可以拿来交叉验证「节次编号 ↔ 作息表」是否自洽
        var startByIndex = {};
        for (k = 0; k < periodTimes.length; k++) startByIndex[periodTimes[k].periodIndex] = periodTimes[k].start;
        var agree = 0;
        var disagree = 0;
        for (k = 0; k < timeSamples.length; k++) {
            var expected = startByIndex[timeSamples[k].period];
            if (!expected) continue;
            if (expected === timeSamples[k].time) agree++;
            else disagree++;
        }
        if (disagree > agree) {
            periodNote = '课表里的上课时间与教务作息表对不上（' + disagree + ' 条不符），已改用空课的默认节次时间，请在学期设置里核对';
            periodTimes = [];
        } else if (disagree > 0) {
            periodNote = '有 ' + disagree + ' 条排课的上课时间与教务作息表对不上，节次时间可能不准，请在学期设置里核对';
        }
        if (table.bad > 0 && !periodNote) {
            periodNote = '教务作息表里有 ' + table.bad + ' 个节次的时间认不出来，已跳过，请在学期设置里核对';
        }
    }

    // ---- 学期元信息 ----------------------------------------------------------
    if (text(term.source) === 'guess') {
        warnings.push('学期编号 ' + termCode + ' 是按本机日期推算的（没能从教务获取），请核对导入的是不是本学期');
    }
    if (periodNote) warnings.push(periodNote);

    var firstDay = mondayOnOrBefore(term.firstDay);
    var firstDayGuessed = !firstDay || text(term.firstDaySource) === 'guess';
    if (!firstDay) firstDay = mondayOnOrBefore(currentMondayIso());
    if (firstDayGuessed) {
        warnings.push('开学日期无法从教务获取，已按最近的周一推算，请在学期管理里核对');
    }

    var calendarWeeks = intOf(term.totalWeeks);
    if (calendarWeeks !== null && (calendarWeeks < 1 || calendarWeeks > MAX_WEEKS)) calendarWeeks = null;

    var totalWeeks = calendarWeeks;
    if (totalWeeks === null) totalWeeks = maxBits > 0 ? maxBits : maxWeek;
    if (!(totalWeeks >= 1)) totalWeeks = FALLBACK_WEEKS;
    if (totalWeeks < maxWeek) totalWeeks = maxWeek;
    if (totalWeeks > MAX_WEEKS) totalWeeks = MAX_WEEKS;

    if (droppedWeeks > 0) {
        warnings.push('有 ' + droppedWeeks + ' 个周次超出 ' + MAX_WEEKS + ' 周（课表数据的上限），没有导入');
    }
    if (skipped > 0) {
        warnings.push('有 ' + skipped + ' 条排课记录缺少课名、星期、节次或周次，没有导入');
    }
    if (notes.bad.length) {
        warnings.push('有 ' + notes.bad.length + ' 段周次认不出来（' + sampleOf(notes.bad, 3) + '），相关排课没有导入');
    }
    if (notes.conflict.length) {
        warnings.push('有 ' + notes.conflict.length + ' 段周次的单双周标记与周次本身矛盾（' + sampleOf(notes.conflict, 3) +
            '），已按该周次本身导入，请核对');
    }
    if (calendarWeeks !== null && maxWeek > calendarWeeks) {
        warnings.push('教务给的学期总周数是 ' + calendarWeeks + ' 周，但课表里有第 ' + maxWeek +
            ' 周的课，已按 ' + totalWeeks + ' 周导入，请核对');
    }

    var clipped = [];
    for (k = 0; k < warnings.length && k < MAX_WARNINGS; k++) clipped.push(clip(warnings[k]));

    var courses = [];
    for (var c = 0; c < order.length; c++) courses.push(byCourse[order[c]]);

    var termName = text(term.name) || termNameFrom(termCode) || '教务导入';

    // periodTimes 缺省时应用会补默认节次表（规范 §4），所以拿不到就整个字段不写，
    // 而不是写一份可能属于另一个校区的作息时间
    var termPayload = { name: termName, firstDay: firstDay, totalWeeks: totalWeeks };
    if (periodTimes.length) termPayload.periodTimes = periodTimes;
    termPayload.courses = courses;

    return JSON.stringify({
        specVersion: 1,
        kind: 'schedule',
        ocrAssisted: false,
        warnings: clipped,
        terms: [termPayload]
    });
})()
