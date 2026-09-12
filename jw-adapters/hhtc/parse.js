(function () {
    // 怀化学院（强智科技「高校综合管理教务系统」，学生端 /jsxsd/）课表解析
    // 移植自 shiguang_warehouse 的 HHTC/hhtc.js（MIT，作者 星河欲转）
    //   https://github.com/XingHeYuZhuan/shiguang_warehouse
    //
    // 上游的核心是**两段**：parseTimetableToModel() 读明细，mergeContinuousLessons() 再把
    // 「同一门课（课名+教师+教室）+ 同一天」的多条明细按「周 × 节次」矩阵重组成连堂块。
    // 已移植的同平台 hynu（衡阳师范学院）只有第一段 —— 这 90 行是两个近克隆脚本最大的差别
    // （相似度 0.775 主要就差在这里，详见 AUDIT.md 的「与 hynu 对照片」）。
    // 这里保留上游那两段（第二段见 mergeRecords），改动集中在五处（AUDIT.md 有逐条说明）：
    //   ① 星期按表头列号对齐（上游按「第几个格子就是星期几」硬算，首列是节次列时整表错一天）；
    //   ② 周次认「单/双」（上游先 split('(')[0] 再数数字，「1-16周(双)」与「1-15(单)」都会退化成每周都上），
    //      串首的教学班序号组（(1)、(1-2)）整组剥掉，不当周次；
    //   ③ 节次认两位连堂写法「[0708节]」（上游正则会把 0708 读成第 708 节）；
    //   ④ 同格里内容相同的两份明细（教务渲染的副本）按内容去重；
    //   ⑤ 方括号里读不出节次时回落本行的「第N节」标签，再读不出才进 warnings。
    //
    // 输入是 extract.js 交出来的原始结构（不碰 DOM，全部按字符串 + 正则处理，是纯函数）：
    //   { now: "2026-09-12", term: { code, name }, cols: 8,
    //     rows: [ { label: "第1-2节", cells: [ { col: 1, text: "…", parts: [原始 HTML] } ] } ] }
    //   cols 是整张表的列数，只有「表头没认全」时用来推断星期列（见下面的 colOf）。
    var data = JSON.parse(__ncInput);
    var rows = data.rows || [];
    var term = data.term || {};

    // 学校作息时间（教务处公布的 10 节）。上游硬编码在 saveAppTimeSlots() 里，逐条照搬。
    var PERIOD_TIMES = [
        { periodIndex: 1, start: '08:20', end: '09:05' },
        { periodIndex: 2, start: '09:15', end: '10:00' },
        { periodIndex: 3, start: '10:20', end: '11:05' },
        { periodIndex: 4, start: '11:15', end: '12:00' },
        { periodIndex: 5, start: '14:20', end: '15:05' },
        { periodIndex: 6, start: '15:15', end: '16:00' },
        { periodIndex: 7, start: '16:20', end: '17:05' },
        { periodIndex: 8, start: '17:15', end: '18:00' },
        { periodIndex: 9, start: '19:30', end: '20:15' },
        { periodIndex: 10, start: '20:25', end: '21:10' }
    ];

    // 空课内置的默认节次表（与 core 的 DefaultPeriodTimes 同值，12 节）。只在课表里出现
    // 「学校公布的 10 节之外」的节次时，用它把那几节的上下课时间补出来（并进 warnings）。
    var DEFAULT_PERIOD_TIMES = [
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

    // 上游没给学期总周数（config 里只有 firstDayOfWeek），只能按常见值假定并进 warnings
    var DEFAULT_TOTAL_WEEKS = 20;
    // 载荷校验的总周数上限（1..30），超出的周次按脏数据丢弃并进 warnings
    var MAX_WEEK = 30;
    // 节次上限：空课内置节次表是 12 节，超出的节次没有上下课时间可补，按读不出处理
    var MAX_PERIOD = 12;
    // 一个格子里放多门课时，教务用一长串减号分隔（上游写死 21/22 个，这里放宽到 5 个以上）
    var DASHES = /-{5,}/;
    var TEACHER_TITLE = /^(教师|老师|任课教师|授课教师|教师姓名)$/;
    var ROOM_TITLE = /^(教室|上课地点|上课教室|地点|教室名称)$/;
    var COURSE_TITLE = /^(课程|课程名称|课程名|科目)$/;
    var SPEC_TITLE = /周次|节次/;   // 别把「时间」并进来：标着「上课时间」的 font 内容是钟点（11:10-11:50），
    // 会被下面的区间正则读成第 10-11 周，凭空造出排课。上游只查固定 title，这个宽匹配是移植时自己加的。
    // 周次串**开头**的序号组：括号里是纯数字或数字区间（(1)、(1-2)）都算教学班序号，不是周次。
    // 强智把序号直接拼在周次前面（(1-2)1-16周），只剥纯数字的话段内的区间分支会先命中
    // 那个 (1-2)，真正的 1-16 周整段丢掉，而且一声不吭。
    // 括号里带「周」「单」「双」或其他字的（(1-2周)、1-16(周)、1-15(单)）是真正的周次形态，不许剥。
    var SERIAL_HEAD = /^\s*[（(]\s*\d{1,3}\s*(?:[-—~至]\s*\d{1,3}\s*)?[)）]/;

    var droppedWeeks = 0;
    var skippedBlocks = 0;
    var overPeriodBlocks = 0;

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
        return String(value).replace(/<br\s*\/?>/gi, ' ').replace(/<[^>]*>/g, '');
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

    // 明细 HTML 里的所有 <font>（含没有 title 的那些 —— 上游 hhtc 的课名就取自后者）。
    // 末尾的 </font> 允许缺失（教务页面的 <font> 偶尔不闭合），那时取到下一个 '<' 为止。
    function fontsOf(blockHtml) {
        var html = String(blockHtml);
        var re = /<font\b([^>]*)>([\s\S]*?)(?:<\/font>|(?=<)|$)/gi;
        var out = [];
        var m = re.exec(html);
        while (m) {
            var title = /title\s*=\s*["']?([^"'>]+)["']?/i.exec(m[1]);
            out.push({ title: title ? clean(title[1]) : '', text: plainText(m[2]) });
            m = re.exec(html);
        }
        return out;
    }

    // 课程名。上游 hhtc 取「没有 title 的 font」（它的选择器是 font:not([title])），
    // 同平台的 hynu 取明细开头的裸文本节点 —— 两种写法都认，顺序：
    //   ① 第一个 <font> 之前的文字（hynu 的形状）
    //   ② 第一个没有 title 的 <font>（hhtc 的形状）
    //   ③ font[title=课程名] 这类显式标注（兜底）
    function nameOf(blockHtml) {
        var head = String(blockHtml).split(/<font\b/i)[0];
        var lines = head.split(/<br\s*\/?>/i);
        for (var i = 0; i < lines.length; i++) {
            var line = plainText(lines[i]);
            if (line) return line;
        }
        var fonts = fontsOf(blockHtml);
        for (var f = 0; f < fonts.length; f++) {
            if (fonts[f].title === '' && fonts[f].text) return fonts[f].text;
        }
        for (var g = 0; g < fonts.length; g++) {
            if (COURSE_TITLE.test(fonts[g].title) && fonts[g].text) return fonts[g].text;
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

    // 同一格里内容相同（或一份是另一份的前缀）的明细只留一份：上游把 .kbcontent 与 .kbcontent1
    // 两份都解析，其中一份常是教务渲染的缩略副本；照单全收会把同一门课记两遍，
    // 或者给缩略副本报一条「解析不出周次」的假警告。返回 { parts, dropped }。
    function dedupeParts(parts) {
        var texts = [];
        var out = [];
        var dropped = 0;
        var i;
        for (i = 0; i < parts.length; i++) texts.push(plainText(parts[i]));
        for (i = 0; i < parts.length; i++) {
            if (!texts[i]) continue;
            var keep = true;
            for (var j = 0; j < parts.length && keep; j++) {
                if (j === i || !texts[j]) continue;
                if (texts[j] === texts[i] && j < i) keep = false;
                if (texts[i].length < texts[j].length && texts[j].indexOf(texts[i]) === 0) keep = false;
            }
            if (keep) out.push(parts[i]);
            else dropped++;
        }
        return { parts: out, dropped: dropped };
    }

    function pushWeek(out, week, oddOnly, evenOnly) {
        if (week < 1) return;
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

    // "1-9,11-17(周)" / "1-16(周)" / "1-15(单)" / "1-16周(双)" / "双周2-16" / "第3周" → 周次数组
    // 串首的教学班序号组（"(1)"、"(1-2)"）不是周次，先整组剥掉（见 SERIAL_HEAD）。
    //
    // 上游是 weekStr.split('(')[0] 之后再数数字：不管「单」「双」写在哪里都会被丢掉，
    // 于是 1-15(单) 与 1-16周(双) 都退化成「每周都上」（第一批在别的适配器上真出过这个错，
    // 而且用例里没有「单」「双」两个字，CI 一路绿的）。这里按段认单双，写在哪都算数。
    function weeksIn(source) {
        var text = clean(source);
        if (!text) return [];
        // 方括号里、以及带「节」的括号里都是节次，不是周次
        text = text.replace(/\[[^\]]*\]/g, ' ').replace(/[（(][^)）]*节[^)）]*[)）]/g, ' ');
        // 串首的序号组（「(1-2)1-16周」）先整组剥掉：那是教学班序号，不是周次。留着它，
        // 下面段内的区间分支会先命中 (1-2)，真正的 1-16 周整段丢掉。只剥串首那一个 ——
        // 串中出现的数字区间仍按原样交给周次逻辑（拿不准的不静默改写）。
        text = text.replace(SERIAL_HEAD, ' ');
        var segments = text.split(/[,，、;；]/);
        var weeks = [];
        for (var i = 0; i < segments.length; i++) {
            var segment = segments[i];
            // 括号里的纯数字序号（「(1)」「(2)」）是课序号，不是周次 —— 当成周次会把它后面的
            // 教师 / 教室 / 真实周次一起吃掉（第一批的坑之二）。
            var loose = segment.replace(/[（(]\s*\d{1,3}\s*[)）]/g, ' ');
            var oddOnly = loose.indexOf('单') >= 0;
            var evenOnly = loose.indexOf('双') >= 0;
            var range = /(\d{1,2})\s*[-—~至]\s*(\d{1,2})/.exec(loose);
            if (range) {
                var start = parseInt(range[1], 10);
                var end = parseInt(range[2], 10);
                for (var w = start; w <= end; w++) pushWeek(weeks, w, oddOnly, evenOnly);
            } else {
                var single = /(\d{1,2})/.exec(loose);
                if (single) pushWeek(weeks, parseInt(single[1], 10), oddOnly, evenOnly);
            }
        }
        return uniqueSorted(weeks);
    }

    // 方括号里的节次："01-02节" / "1-2节" / "第3节" 之外的写法都认；
    // 连堂也写成 "0708节" / "030405节"（两位一节），按两位切 —— 上游正则会把 0708 读成第 708 节。
    function periodsIn(source) {
        var body = clean(source).replace(/[第节\s]/g, '').replace(/[（）()[\]]/g, '');
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

    // 行标签（课表首列）里的节次："第1-2节" / "1-2节" / "第3节"。
    // 只认整串就是节次的标签 —— "上午"、"晚上"、"第1-2节 (01,02小节) 08:00-09:35" 这类
    // 一律返回 null，免得从标签里读出一个离谱的节次。
    var LABEL_PERIODS = /^第?\s*(\d{1,2})\s*(?:[-—~至]\s*(\d{1,2}))?\s*节?$/;

    function labelPeriodsOf(text) {
        var m = LABEL_PERIODS.exec(clean(text));
        if (!m) return null;
        var start = parseInt(m[1], 10);
        var end = m[2] ? parseInt(m[2], 10) : start;
        if (!(start >= 1) || end < start) return null;
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

    // 上游的 mergeContinuousLessons()：把「同一门课（课名+教师+教室）+ 同一天」的多条明细
    // 拆成「第 N 周 → 那一周占的节次集合」的矩阵，再按连续的节次段重组。效果是
    //   ① 周次不同的同一门课合成一条（1-8 周 + 9-16 周 → 1-16 周）；
    //   ② 相邻的两段节次（01-02 + 03-04）并成一个连堂块（1-4 节）；
    //   ③ 单双周在这一步已经落成具体周次，最后由 runsOf() 还原成 ODD / EVEN 段。
    // 与上游的差别只有两处：矩阵的周次上限按我们的载荷上限（30 周，上游写死 50），
    // 以及用数组记住分组与块的先后顺序（不依赖 JS 对象的按键遍历顺序，Rhino 上不可靠）。
    function mergeRecords(records) {
        var order = [];
        var groups = {};
        var i;
        for (i = 0; i < records.length; i++) {
            var r = records[i];
            var key = 'g' + JSON.stringify([r.name, r.teacher, r.position, r.day]);
            var group = groups[key];
            if (!group) {
                group = { name: r.name, teacher: r.teacher, position: r.position, day: r.day, sections: [] };
                groups[key] = group;
                order.push(key);
            }
            for (var w = 0; w < r.weeks.length; w++) {
                var week = r.weeks[w];
                if (!(week >= 1) || week > MAX_WEEK) continue;
                var bucket = group.sections[week];
                if (!bucket) {
                    bucket = [];
                    group.sections[week] = bucket;
                }
                for (var s = r.startSection; s <= r.endSection; s++) {
                    if (bucket.indexOf(s) < 0) bucket.push(s);
                }
            }
        }

        var merged = [];
        for (i = 0; i < order.length; i++) {
            var g = groups[order[i]];
            var weekOrder = [];
            for (var wk = 1; wk <= MAX_WEEK; wk++) {
                if (g.sections[wk] && g.sections[wk].length) weekOrder.push(wk);
            }
            var blockOrder = [];
            var blockWeeks = {};
            for (var wi = 0; wi < weekOrder.length; wi++) {
                var week2 = weekOrder[wi];
                var secs = uniqueSorted(g.sections[week2]);
                var start = secs[0];
                var prev = secs[0];
                for (var si = 1; si < secs.length; si++) {
                    var curr = secs[si];
                    if (curr === prev + 1) {
                        prev = curr;
                        continue;
                    }
                    noteBlock(blockOrder, blockWeeks, start, prev, week2);
                    start = curr;
                    prev = curr;
                }
                noteBlock(blockOrder, blockWeeks, start, prev, week2);
            }
            for (var bi = 0; bi < blockOrder.length; bi++) {
                var bound = blockOrder[bi];
                merged.push({
                    name: g.name,
                    teacher: g.teacher,
                    position: g.position,
                    day: g.day,
                    startSection: bound.start,
                    endSection: bound.end,
                    weeks: blockWeeks[bound.key]
                });
            }
        }
        return merged;
    }

    function noteBlock(order, weeks, start, end, week) {
        var key = start + '-' + end;
        if (!weeks[key]) {
            weeks[key] = [];
            order.push({ key: key, start: start, end: end });
        }
        weeks[key].push(week);
    }

    // 星期表头：找「没有课程内容、且有 ≥4 个星期标签」的那一行，按它的列号取每天那一列。
    // 上游把「第几个格子」直接当星期几，课表首列是节次时整张表会错一天。
    var headerRow = -1;
    var dayCol = [0, -1, -1, -1, -1, -1, -1, -1];
    var bestLabels = 0;
    var r;
    var c;
    for (r = 0; r < rows.length; r++) {
        var line = rows[r].cells || [];
        var hasContent = false;
        var cols = [0, -1, -1, -1, -1, -1, -1, -1];
        var labels = 0;
        for (c = 0; c < line.length; c++) {
            if (line[c].parts && line[c].parts.length) hasContent = true;
            var day = dayOfLabel(line[c].text);
            if (day >= 1 && day <= 7 && cols[day] < 0) {
                cols[day] = line[c].col === undefined ? c : line[c].col;
                labels++;
            }
        }
        if (hasContent || labels < 4) continue;
        if (labels > bestLabels) {
            bestLabels = labels;
            headerRow = r;
            dayCol = cols;
        }
    }

    // 表宽（extract 交出来的列数）。只有「表头没认全」时才用得上：兜底是
    // 「每行最后 7 列对应周一到周日」，而这里的「列」必须是**整张表**的列 ——
    // 没课的格子 extract 不会交出来，按某一行的最后一格算会把有课的列误当成星期列。
    var tableCols = Number(data.cols) || 0;
    if (!(tableCols > 0)) {
        for (r = 0; r < rows.length; r++) {
            var span = rows[r].cells || [];
            for (c = 0; c < span.length; c++) {
                var at = span[c].col === undefined ? c : span[c].col;
                if (at + 1 > tableCols) tableCols = at + 1;
            }
        }
    }
    var offset = tableCols > 7 ? tableCols - 7 : 0;

    // 每天对应哪一列：表头认出来的优先；表头没认出来的天，按「最后 7 列」的位置推断。
    // 推断出来的列要是已经被别的天占了，就**不猜**（宁可少认一天，也不把课排到错的星期）。
    var colOf = [0, -1, -1, -1, -1, -1, -1, -1];
    var unplaced = 0;
    for (var dd = 1; dd <= 7; dd++) {
        colOf[dd] = dayCol[dd];
        if (colOf[dd] >= 0) continue;
        var guess = offset + dd - 1;
        if (guess >= tableCols) guess = -1;
        for (var kk = 1; kk <= 7; kk++) {
            if (colOf[kk] === guess) guess = -1;
        }
        colOf[dd] = guess;
        if (guess < 0) unplaced++;
    }

    // 逐格读明细 → 排课记录（还没合并、也还没切成我们的 weekType 段）
    var records = [];
    for (r = 0; r < rows.length; r++) {
        if (r === headerRow) continue;
        var row = rows[r];
        var cells = row.cells || [];
        var labelPeriods = labelPeriodsOf(row.label);
        for (c = 0; c < cells.length; c++) {
            var cell = cells[c];
            var cellCol = cell.col === undefined ? c : cell.col;
            var dayNo = 0;
            for (var d = 1; d <= 7; d++) {
                if (colOf[d] === cellCol) dayNo = d;
            }
            if (dayNo < 1) continue;
            var kept = dedupeParts(cell.parts || []);
            for (var p = 0; p < kept.parts.length; p++) {
                var blocks = String(kept.parts[p]).split(DASHES);
                for (var b = 0; b < blocks.length; b++) {
                    var blockHtml = blocks[b];
                    if (plainText(blockHtml) === '') continue;
                    var name = nameOf(blockHtml);
                    if (!name) {
                        skippedBlocks++;
                        continue;
                    }
                    var fonts = fontsOf(blockHtml);
                    var teachers = titleTexts(fonts, TEACHER_TITLE);
                    var rooms = titleTexts(fonts, ROOM_TITLE);
                    var specs = specsOf(blockHtml);
                    if (!specs.length) {
                        skippedBlocks++;
                        continue;
                    }
                    for (var s = 0; s < specs.length; s++) {
                        var weeks = weeksIn(specs[s].weeks);
                        if (!weeks.length) {
                            skippedBlocks++;
                            continue;
                        }
                        // 方括号里读不出节次时回落本行的「第N节」标签（上游直接丢这块）
                        var periods = periodsIn(specs[s].periods) || labelPeriods;
                        if (!periods) {
                            skippedBlocks++;
                            continue;
                        }
                        if (periods.end > MAX_PERIOD) {
                            overPeriodBlocks++;
                            continue;
                        }
                        records.push({
                            name: name,
                            teacher: teachers.length ? teachers.join(',') : '',
                            position: rooms.length ? rooms[0] : '',
                            day: dayNo,
                            startSection: periods.start,
                            endSection: periods.end,
                            weeks: weeks
                        });
                    }
                }
            }
        }
    }

    if (!records.length) {
        throw new Error(
            '本学期没有解析到任何课程：可能是还没排课，或教务页面改了结构（也可能登录状态已失效）'
        );
    }

    // 与上游一致的排序（星期 → 起始节 → 课名），后面再按块排序，输出顺序不依赖遍历顺序
    var mergedRecords = mergeRecords(records);
    mergedRecords.sort(function (a, b) {
        if (a.day !== b.day) return a.day - b.day;
        if (a.startSection !== b.startSection) return a.startSection - b.startSection;
        if (a.endSection !== b.endSection) return a.endSection - b.endSection;
        if (a.name !== b.name) return a.name < b.name ? -1 : 1;
        if (a.teacher !== b.teacher) return a.teacher < b.teacher ? -1 : 1;
        if (a.position !== b.position) return a.position < b.position ? -1 : 1;
        return 0;
    });

    var order = [];
    var byCourse = {};
    var maxWeek = 0;
    var maxPeriod = 0;
    for (var mi = 0; mi < mergedRecords.length; mi++) {
        var rec = mergedRecords[mi];
        // 课程按「课名 + 教师」归并（上游按课名+教师+教室+星期分组，同名不同教室会拆成两门课）
        var courseKey = 'c' + JSON.stringify([rec.name, rec.teacher]);
        var course = byCourse[courseKey];
        if (!course) {
            course = { name: rec.name, teacher: rec.teacher || null, note: null, blocks: [], seen: {} };
            byCourse[courseKey] = course;
            order.push(courseKey);
        }
        var runs = runsOf(rec.weeks);
        for (var ri = 0; ri < runs.length; ri++) {
            var run = runs[ri];
            var block = {
                dayOfWeek: rec.day,
                startPeriod: rec.startSection,
                endPeriod: rec.endSection,
                startWeek: run.start,
                endWeek: run.end,
                weekType: run.weekType,
                location: rec.position || null
            };
            var blockKey = JSON.stringify([block.dayOfWeek, block.startPeriod, block.endPeriod,
                block.startWeek, block.endWeek, block.weekType, block.location || '']);
            if (course.seen[blockKey]) continue;
            course.seen[blockKey] = true;
            course.blocks.push(block);
            if (block.endWeek > maxWeek) maxWeek = block.endWeek;
            if (block.endPeriod > maxPeriod) maxPeriod = block.endPeriod;
        }
    }

    var courses = [];
    for (var oi = 0; oi < order.length; oi++) {
        var item = byCourse[order[oi]];
        delete item.seen;
        item.blocks.sort(function (a, b) {
            if (a.dayOfWeek !== b.dayOfWeek) return a.dayOfWeek - b.dayOfWeek;
            if (a.startPeriod !== b.startPeriod) return a.startPeriod - b.startPeriod;
            if (a.endPeriod !== b.endPeriod) return a.endPeriod - b.endPeriod;
            if (a.startWeek !== b.startWeek) return a.startWeek - b.startWeek;
            if (a.endWeek !== b.endWeek) return a.endWeek - b.endWeek;
            if (a.weekType !== b.weekType) return a.weekType < b.weekType ? -1 : 1;
            var la = a.location || '';
            var lb = b.location || '';
            if (la !== lb) return la < lb ? -1 : 1;
            return 0;
        });
        courses.push({ name: item.name, teacher: item.teacher, note: item.note, blocks: item.blocks });
    }

    var totalWeeks = maxWeek > DEFAULT_TOTAL_WEEKS ? maxWeek : DEFAULT_TOTAL_WEEKS;
    if (totalWeeks > MAX_WEEK) totalWeeks = MAX_WEEK;

    // 连堂块要覆盖到每一节：学校公布的 10 节之外的节次（课表里真出现了）用空课内置默认表补上
    var periodTimes = [];
    var filledPeriods = [];
    for (var pi = 0; pi < PERIOD_TIMES.length; pi++) periodTimes.push(PERIOD_TIMES[pi]);
    for (var need = PERIOD_TIMES.length + 1; need <= maxPeriod; need++) {
        var donor = null;
        for (var di = 0; di < DEFAULT_PERIOD_TIMES.length; di++) {
            if (DEFAULT_PERIOD_TIMES[di].periodIndex === need) donor = DEFAULT_PERIOD_TIMES[di];
        }
        if (!donor) continue;
        periodTimes.push({ periodIndex: donor.periodIndex, start: donor.start, end: donor.end });
        filledPeriods.push(donor.periodIndex);
    }

    var termName = clean(term.name) || termNameFromCode(term.code);

    // extract.js 一定会带 now（提取时刻）；缺了就没法推算开学日，明确报错而不是瞎猜一个日期
    var firstDay = mondayOf(data.now);
    if (!firstDay) {
        throw new Error('提取数据里缺少 now（提取时刻），无法推算开学日期');
    }

    // 推算/假定出来的东西逐条说清楚（§4.2）：这些值在库里和真值长得一模一样，
    // 不说明用户就没有机会发现「现在第几周」是错的。
    var warnings = [
        '开学日期无法从教务获取，已按最近的周一（' + firstDay + '）推算，请在「学期管理」里核对'
    ];
    if (maxWeek > DEFAULT_TOTAL_WEEKS) {
        warnings.push(
            '课表里最晚排到第 ' + maxWeek + ' 周，学期总周数已从默认的 ' + DEFAULT_TOTAL_WEEKS +
                ' 周抬高到 ' + totalWeeks + ' 周，请在「学期管理」里核对'
        );
    } else {
        warnings.push(
            '教务页面不提供学期总周数与作息时间：总周数按 ' + totalWeeks + ' 周、' +
                '节次时间按怀化学院公布的 ' + PERIOD_TIMES.length + ' 节作息表设定，请在「学期管理」里核对'
        );
    }
    if (bestLabels < 7) {
        warnings.push(
            '课表的星期表头没有认全（认出 ' + bestLabels + ' 天），没认出来的天已按「每行最后 7 列」的位置推断' +
                (unplaced ? '，有 ' + unplaced + ' 天推断不出对应的列（那些列里的课已跳过）' : '') +
                '，请核对导入结果'
        );
    }
    if (droppedWeeks > 0) {
        warnings.push('有 ' + droppedWeeks + ' 条周次超出 ' + MAX_WEEK + ' 周，已按脏数据丢弃');
    }
    if (overPeriodBlocks > 0) {
        warnings.push(
            '有 ' + overPeriodBlocks + ' 个课程块的节次超出 ' + MAX_PERIOD + ' 节，已跳过（欢迎反馈给适配器）'
        );
    }
    if (skippedBlocks > 0) {
        warnings.push(
            '有 ' + skippedBlocks + ' 个课程块没能解析出周次或节次，已跳过（教务页面结构可能已调整，欢迎反馈给适配器）'
        );
    }
    if (filledPeriods.length) {
        warnings.push(
            '课表里出现了第 ' + filledPeriods.join('、') + ' 节（超出怀化学院公布的 ' +
                PERIOD_TIMES.length + ' 节作息表），这几节的上下课时间已用空课内置默认表补齐，请核对'
        );
    }
    if (!termName) {
        termName = '怀化学院课表';
        warnings.push('没能识别出学年学期名称，学期名已用「怀化学院课表」占位，请在「学期管理」里改名');
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
