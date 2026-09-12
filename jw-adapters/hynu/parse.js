(function () {
    // 衡阳师范学院（强智 · 高校综合管理教务系统）课表解析
    // 移植自 shiguang_warehouse 的 HYNU/hynu_01.js（MIT，作者 星河欲转）
    //   https://github.com/XingHeYuZhuan/shiguang_warehouse
    // 上游的核心是 parseTimetableToModel() + parseWeeks()：读 #timetable 里每个格子
    // div.kbcontent 的明细，按「课程名 / font[title=教师|周次(节次)|教室]」取字段。
    // 这里保持同一套字段来源，改动只在两处语义（详见 AUDIT.md）：
    //   ① 星期按表头列号对齐（上游按「第几个格子就是星期几」硬算，课表首列是节次时会整表错一天）；
    //   ② 周次认「单/双」（上游 split('(')[0] 会把 1-15(单) 当成每周都上），
    //      并按我们的规范切成极大段 + weekType。
    //
    // 输入是 extract.js 交出来的原始结构：
    //   { now: "2026-09-12", term: { code, name },
    //     rows: [ [ { text, parts: [原始 HTML], col, span }, … ], … ] }
    //   col 是格子所在的网格列号（extract.js 把 colspan/rowspan 都算进去了），span 是跨列数；
    //   缺了 col 的老载荷按「第几个格子」退回，行为与历史一致（见 colOf）。
    // 这里不碰 DOM（CI 的 Rhino 里没有），全部按字符串 + 正则处理，是纯函数。
    var data = JSON.parse(__ncInput);
    var rows = data.rows || [];
    var term = data.term || {};

    // 学校作息时间（教务处公布的 12 节）。上游硬编码在 saveAppTimeSlots() 里，逐条照搬。
    var PERIOD_TIMES = [
        { periodIndex: 1, start: '08:30', end: '09:15' },
        { periodIndex: 2, start: '09:25', end: '10:10' },
        { periodIndex: 3, start: '10:30', end: '11:15' },
        { periodIndex: 4, start: '11:25', end: '12:10' },
        { periodIndex: 5, start: '14:30', end: '15:15' },
        { periodIndex: 6, start: '15:25', end: '16:10' },
        { periodIndex: 7, start: '16:30', end: '17:15' },
        { periodIndex: 8, start: '17:25', end: '18:10' },
        { periodIndex: 9, start: '19:30', end: '20:15' },
        { periodIndex: 10, start: '20:25', end: '21:10' },
        { periodIndex: 11, start: '21:20', end: '22:05' },
        { periodIndex: 12, start: '22:15', end: '23:00' }
    ];

    // 上游写死的学期总周数（教务页面不给这个值，只能照抄并进 warnings）
    var DEFAULT_TOTAL_WEEKS = 20;
    // 载荷校验的总周数上限（1..30），超出的周次按脏数据丢弃并进 warnings
    var MAX_WEEK = 30;
    // 一个格子里放多门课时，教务用一长串减号分隔（上游写死 21/22 个，这里放宽到 5 个以上）
    var DASHES = /-{5,}/;
    var TEACHER_TITLE = /^(教师|老师|任课教师|授课教师|教师姓名)$/;
    var ROOM_TITLE = /^(教室|上课地点|上课教室|地点|教室名称)$/;
    var COURSE_TITLE = /^(课程|课程名称|课程名|科目)$/;
    var SPEC_TITLE = /周次|节次/;   // 别把「时间」并进来：标着「上课时间」的 font 内容是钟点（11:10-11:50），
    // 会被下面的区间正则读成第 10-11 周，凭空造出排课。上游只查固定 title，这个宽匹配是移植时自己加的。

    var droppedWeeks = 0;
    var skippedBlocks = 0;

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

    function titleTexts(fonts, pattern) {
        var out = [];
        for (var i = 0; i < fonts.length; i++) {
            if (!pattern.test(fonts[i].title)) continue;
            var text = fonts[i].text;
            if (text && out.indexOf(text) < 0) out.push(text);
        }
        return out;
    }

    // "周次(节次)" 一条 font 里既有周次也有节次（上游这所学校就是），
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

    // "1-9,11-17" / "1-4,6-8,10-16" / "1-15(单)" / "第3周" → 周次数组
    function weeksIn(source) {
        var text = clean(source).replace(/\[[^\]]*\]/g, ' ').replace(/[（(]\s*周\s*[)）]/g, ' ');
        var segments = text.split(/[,，、;；]/);
        var weeks = [];
        for (var i = 0; i < segments.length; i++) {
            var segment = segments[i];
            var oddOnly = segment.indexOf('单') >= 0;
            var evenOnly = segment.indexOf('双') >= 0;
            var range = /(\d{1,2})\s*[-—~至]\s*(\d{1,2})/.exec(segment);
            if (range) {
                var start = parseInt(range[1], 10);
                var end = parseInt(range[2], 10);
                for (var w = start; w <= end; w++) pushWeek(weeks, w, oddOnly, evenOnly);
            } else {
                var single = /(\d{1,2})/.exec(segment);
                if (single) pushWeek(weeks, parseInt(single[1], 10), oddOnly, evenOnly);
            }
        }
        return uniqueSorted(weeks);
    }

    // 节次：方括号里的内容，"01-02节" / "1-2节" / "第一大节" 之外的写法都认；
    // 连堂也写成 "0102节" / "030405节"（两位一节），按两位切。
    function periodsIn(source) {
        var body = clean(source).replace(/[第节\s]/g, '').replace(/[（）()]/g, '');
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

    // 首列是不是「节次」列（"第1-2节" / "1-2" / "第3节"）
    function isPeriodLabel(value) {
        return /^第?\s*\d{1,2}\s*([-—~至]\s*\d{1,2})?\s*节?$/.test(clean(value));
    }

    // 格子所在的**网格列号**：extract.js 交出来的 col（colspan/rowspan 都算进去了）。
    // 老一版载荷（或手写的用例）没有 col 时退回「第几个格子」，此时行为与历史一致。
    function colOf(cell, fallbackIndex) {
        return (typeof cell.col === 'number' && cell.col >= 0) ? cell.col : fallbackIndex;
    }

    function spanOf(cell) {
        return (typeof cell.span === 'number' && cell.span >= 1) ? cell.span : 1;
    }

    // 取「网格第 want 列」上的格子：一格覆盖 [col, col + span)，跨列的合并格在它覆盖到的
    // 每一列上都能取到（合并格里的课会落到它跨的每一天，这种情况进 warnings 说明）。
    function cellAtGridCol(row, want) {
        if (want < 0) return null;
        for (var i = 0; i < row.length; i++) {
            var start = colOf(row[i], i);
            if (want >= start && want < start + spanOf(row[i])) return row[i];
        }
        return null;
    }

    // 表宽 = 整张表用到的最大网格列数（把 colspan 算进去）。无表头兜底的窗口起点要用它，
    // 不能用某一行的格子数：行中部有合并格时那一行会少一个格子，按格子数算会整行错一天。
    function gridWidthOf(allRows) {
        var width = 0;
        for (var r = 0; r < allRows.length; r++) {
            var row = allRows[r] || [];
            for (var c = 0; c < row.length; c++) {
                var end = colOf(row[c], c) + spanOf(row[c]);
                if (end > width) width = end;
            }
        }
        return width;
    }

    var tableWidth = gridWidthOf(rows);

    function termNameFromCode(code) {
        var m = /^(\d{4})-(\d{4})-(\d)$/.exec(clean(code));
        if (!m) return '';
        if (m[3] === '1') return m[1] + '-' + m[2] + '学年第一学期';
        if (m[3] === '2') return m[1] + '-' + m[2] + '学年第二学期';
        if (m[3] === '3') return m[1] + '-' + m[2] + '学年第三学期';
        return m[1] + '-' + m[2] + '学年第' + m[3] + '学期';
    }

    // 星期表头：找「没有课程内容、且有 ≥4 个星期标签」的那一行，按它的列号取每天那一列。
    // 上游把「第几个格子」直接当星期几，课表首列是节次时整张表会错一天。
    var headerRow = -1;
    var dayCol = [0, -1, -1, -1, -1, -1, -1, -1];
    var bestLabels = 0;
    for (var r = 0; r < rows.length; r++) {
        var hasContent = false;
        var cols = [0, -1, -1, -1, -1, -1, -1, -1];
        var labels = 0;
        for (var c = 0; c < rows[r].length; c++) {
            if (rows[r][c].parts && rows[r][c].parts.length) hasContent = true;
            var day = dayOfLabel(rows[r][c].text);
            if (day >= 1 && day <= 7 && cols[day] < 0) {
                cols[day] = colOf(rows[r][c], c);
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

    var order = [];
    var byCourse = {};

    // 每天落在网格第几列：
    //   ① 表头认出来的天用表头的列号（最可靠）；
    //   ② 表头没认出来的天，按**表宽**推「整张表最后 7 列是周一到周日」——
    //      这里绝不能用本行的格子数：行中部有合并格时那一行会少一个格子，
    //      按本行格子数算会把整行挪一天（缺陷单：无表头 + 行中部少格 →
    //      网格第 1 列（星期一）的课被读成星期二）；
    //   ③ 推出来的列已经归了别的天、或超出表宽时**不猜**：宁可少认一天，
    //      也不把课排到错的星期（不猜和少认的都进 warnings）。
    var fallbackStart = tableWidth > 7 ? tableWidth - 7 : 0;
    if (tableWidth <= 7) {
        // 表宽不到 8 列：7 列窗口里会混进首列的节次标签列，往后挪一格
        for (var fr = 0; fr < rows.length; fr++) {
            if (rows[fr].length && isPeriodLabel(rows[fr][0].text)) {
                fallbackStart = 1;
                break;
            }
        }
    }

    var dayColOf = [0, -1, -1, -1, -1, -1, -1, -1];
    var dayGuessed = [false, false, false, false, false, false, false, false];
    var usedCols = {};
    var guessedDays = 0;
    var unplacedDays = 0;
    var dd;
    for (dd = 1; dd <= 7; dd++) {
        if (bestLabels >= 4 && dayCol[dd] >= 0) {
            dayColOf[dd] = dayCol[dd];
            usedCols[dayCol[dd]] = true;
        }
    }
    for (dd = 1; dd <= 7; dd++) {
        if (dayColOf[dd] >= 0) continue;
        var guess = fallbackStart + dd - 1;
        if (guess < 0 || guess >= tableWidth || usedCols[guess]) {
            unplacedDays++;
            continue;
        }
        dayColOf[dd] = guess;
        dayGuessed[dd] = true;
        usedCols[guess] = true;
        guessedDays++;
    }

    // 跨列且有内容的格子数：这种格子的课会落到它覆盖的每一天，得跟用户说一声
    var mergedCells = 0;
    // 星期是按表宽推出来的安排条数：这些安排的星期可能不准，warnings 里要点名
    var guessedBlocks = 0;

    for (var ri = 0; ri < rows.length; ri++) {
        if (ri === headerRow) continue;
        var row = rows[ri];
        var countedMerged = [];
        for (var d = 1; d <= 7; d++) {
            var cell = cellAtGridCol(row, dayColOf[d]);
            if (!cell) continue;
            var parts = cell.parts || [];
            if (spanOf(cell) > 1 && parts.length && countedMerged.indexOf(cell) < 0) {
                countedMerged.push(cell);
                mergedCells++;
            }
            for (var p = 0; p < parts.length; p++) {
                var blocks = String(parts[p]).split(DASHES);
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
                    var key = name + '\u0000' + teachers.join(',');
                    var course = null;
                    for (var s = 0; s < specs.length; s++) {
                        var weeks = weeksIn(specs[s].weeks);
                        var periods = periodsIn(specs[s].periods);
                        if (!weeks.length || !periods) {
                            skippedBlocks++;
                            continue;
                        }
                        // 课程要等真产出一条安排才登记：周次/节次解析不出来的块只进 warnings，
                        // 不能在课表里留下一门「没有任何安排」的空课。
                        if (!course) {
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
                            course = byCourse[key];
                        }
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
                            var blockKey = [block.dayOfWeek, block.startPeriod, block.endPeriod,
                                block.startWeek, block.endWeek, block.weekType,
                                block.location || ''].join('|');
                            if (course.seen[blockKey]) continue;
                            course.seen[blockKey] = true;
                            if (dayGuessed[d]) guessedBlocks++;
                            course.blocks.push(block);
                        }
                    }
                }
            }
        }
    }

    if (order.length === 0) {
        throw new Error(
            '本学期没有解析到任何课程：可能是还没排课，或教务页面改了结构（也可能登录状态已失效）'
        );
    }

    var maxWeek = 0;
    var courses = [];
    for (var oi = 0; oi < order.length; oi++) {
        var item = byCourse[order[oi]];
        for (var bi = 0; bi < item.blocks.length; bi++) {
            if (item.blocks[bi].endWeek > maxWeek) maxWeek = item.blocks[bi].endWeek;
        }
        courses.push({ name: item.name, teacher: item.teacher, note: item.note, blocks: item.blocks });
    }

    var totalWeeks = maxWeek > DEFAULT_TOTAL_WEEKS ? maxWeek : DEFAULT_TOTAL_WEEKS;
    if (totalWeeks > MAX_WEEK) totalWeeks = MAX_WEEK;

    var termName = clean(term.name) || termNameFromCode(term.code);

    // extract.js 一定会带 now（提取时刻）；缺了就没法推算开学日，明确报错而不是瞎猜一个日期
    var firstDay = mondayOf(data.now);
    if (!firstDay) {
        throw new Error('提取数据里缺少 now（提取时刻），无法推算开学日期');
    }

    // 推算/假定出来的东西逐条说清楚（§4.2）：这些值在库里和真值长得一模一样，
    // 不说明用户就没有机会发现「现在第几周」是错的。
    var warnings = [
        '开学日期无法从教务获取，已按最近的周一（' + firstDay + '）推算，请在「学期管理」里核对',
        '教务页面不提供学期总周数与作息时间：总周数按 ' + totalWeeks + ' 周、' +
            '节次时间按适配器内置的 12 节设定，请在「学期管理」里核对'
    ];
    if (droppedWeeks > 0) {
        warnings.push('有 ' + droppedWeeks + ' 条周次超出 ' + MAX_WEEK + ' 周，已按脏数据丢弃');
    }
    if (skippedBlocks > 0) {
        warnings.push(
            '有 ' + skippedBlocks + ' 个课程块没能解析出周次或节次，已跳过（教务页面结构可能已调整，欢迎反馈给适配器）'
        );
    }
    if (mergedCells > 0) {
        warnings.push(
            '有 ' + mergedCells + ' 个课程格子跨了多列（合并单元格）：已按它覆盖到的每一天各记一次，' +
            '哪几天真的上课请核对导入结果'
        );
    }
    if (bestLabels < 4) {
        warnings.push(
            '课表里没找到星期表头，已按表宽 ' + tableWidth + ' 列推算（从第 ' + (fallbackStart + 1) +
            ' 列起往右依次是周一到周日）：这样排出来的 ' + guessedBlocks + ' 条安排的星期可能不准，请核对导入结果'
        );
        if (tableWidth < 8) {
            warnings.push(
                '课表只有 ' + tableWidth + ' 列，可能没有显示完整的 7 天：没显示的那几天按「没有课」处理，请核对'
            );
        }
    } else if (guessedDays > 0) {
        warnings.push(
            '星期表头只认出了 ' + bestLabels + ' 天，剩下 ' + guessedDays +
            ' 天已按表宽推算：这 ' + guessedBlocks + ' 条安排的星期可能不准，请核对导入结果'
        );
    }
    if (bestLabels >= 4 && unplacedDays > 0) {
        warnings.push(
            '有 ' + unplacedDays + ' 天没能在课表里对上一列，这几天的课可能没导进来，请核对'
        );
    }
    if (!termName) {
        termName = '衡阳师范学院课表';
        warnings.push('没能识别出学年学期名称，学期名已用「衡阳师范学院课表」占位，请在「学期管理」里改名');
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
