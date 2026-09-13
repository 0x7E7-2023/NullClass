(function () {
    // 湖南商务职业技术学院 教务适配器（湖南强智 · 学生端 /jsxsd/）—— 第二步：纯转换。
    //
    // 移植自 shiguang_warehouse 的 HNVCC/HNVCC_01.js
    //   https://github.com/XingHeYuZhuan/shiguang_warehouse  （MIT，作者 星河欲转）
    //
    // 上游核心是 fetchTimetable() 取回 HTML 之后的 parseTimetable() + parseWeeks() +
    // parseSections() + mergeCourses()：读 #timetable 里 tbody>tr 的每一行，第一格是节次、
    // 第 1..7 格是周一到周日；格子里 .item-box 的每个直接子 <p> 是一门课（课名），紧随其后的
    // .tch-name 给教师，带 item1.png 图标的那个 div 的两个 span 给地点与周次；最后由
    // mergeCourses() 把连堂的课并成一块。
    //
    // 字段来源与上游一致，语义改了六处（逐条留证见 AUDIT.md「与上游的差异」）：
    //   ① 周次认「单」「双」，且认标记落在**四个**位置上的写法：「1-16周(单)」（标记在「周」后）、
    //      「(单)1-16周」（标记在前）、「1-16(单周)」（标记在括号里）、以及用分隔符自成一段跟在
    //      周次后面的「1-16周,双」（含「、双」「;双」「,双周」，标记在**末尾**也算 —— 这一种要
    //      并入它前面最近的那个周次段）。上游的
    //      /第(.*?)(周|\()/ 取到「周」就收工，**「周」后面的括号整段丢掉** —— 双周课于是
    //      退化成「每周都上」而且不报警（同批 hbmu 的真实故障，测试方案 §3.1）。
    //   ② 星期按**表头列号**对齐（extract.js 交出来的是算过 colspan/rowspan 的网格列号）。
    //      上游把「第几个格子」直接当星期几，首列不是节次列时整表错一天；没有表头时按网格
    //      列号兜底（**不许** row.length-7 那种按数组长度猜列），认不出就进 warnings、不猜。
    //   ③ 节次先摘掉钟点再解。上游的 /(\d+)\s*-\s*(\d+)/ 在带上下课时间的格子上会先匹配到
    //      「08:20-09:05」里的「20-09」，算出 start=20 > end=9 —— 载荷里 endPeriod < startPeriod
    //      会让**整次导入失败**（不是跳过这一条）。
    //   ④ 作息表只到第 12 节，课表里出现更晚的节次就按每节 45 分钟、课间 10 分钟顺延补出。
    //      补出来的时刻必须落在 00:00-23:59 内：第 15 节会算出 24:10，一个非法时刻会让
    //      **整份载荷被拒**（JwPayloadCodec 的时刻只收 00:00-23:59），所以越界一律截断到
    //      23:59 并进 warnings，再往后补不出的节次课块保留、只是没有时刻。
    //   ⑤ 作息季节（上游第二个弹窗问用户）改由提取日期判定：5-9 月夏季作息、其余冬季作息，
    //      并进 warnings —— 教务页面根本不给作息时间，用户必须有机会核对。
    //   ⑥ 周次/节次/课名/星期认不出的块逐条计数进 warnings（不许静默丢课）。
    //
    // 输入是 extract.js 交出来的原始结构：
    //   { now, term: {code,name}, totalWeeksText, rows: [ { cells: [ {col,colSpan,rowSpan,text,boxes} ] } ] }
    //   boxes 是每门课的**原始文字**：{ name, teacher, spans: [地点/周次的原始 span 文字] }
    // 这里不碰 DOM（CI 的 Rhino 里没有），全部按字符串 + 正则处理，是纯函数。
    var data = JSON.parse(__ncInput);
    var rows = data.rows || [];
    var term = data.term || {};

    // 学校作息时间（12 节）：上游 generateSummerTimeSlots() / generateWinterTimeSlots()
    // 两张表逐条照搬，只多了一句季节判定（见 ⑤）。
    var SUMMER_PERIODS = [
        { periodIndex: 1, start: '08:20', end: '09:05' },
        { periodIndex: 2, start: '09:15', end: '10:00' },
        { periodIndex: 3, start: '10:20', end: '11:05' },
        { periodIndex: 4, start: '11:15', end: '12:00' },
        { periodIndex: 5, start: '14:00', end: '14:45' },
        { periodIndex: 6, start: '14:55', end: '15:40' },
        { periodIndex: 7, start: '15:55', end: '16:40' },
        { periodIndex: 8, start: '16:50', end: '17:35' },
        { periodIndex: 9, start: '19:00', end: '19:45' },
        { periodIndex: 10, start: '19:55', end: '20:40' },
        { periodIndex: 11, start: '20:45', end: '21:30' },
        { periodIndex: 12, start: '21:35', end: '22:20' }
    ];
    var WINTER_PERIODS = [
        { periodIndex: 1, start: '08:20', end: '09:05' },
        { periodIndex: 2, start: '09:15', end: '10:00' },
        { periodIndex: 3, start: '10:20', end: '11:05' },
        { periodIndex: 4, start: '11:15', end: '12:00' },
        { periodIndex: 5, start: '14:30', end: '15:15' },
        { periodIndex: 6, start: '15:25', end: '16:10' },
        { periodIndex: 7, start: '16:25', end: '17:10' },
        { periodIndex: 8, start: '17:20', end: '18:05' },
        { periodIndex: 9, start: '19:00', end: '19:45' },
        { periodIndex: 10, start: '19:55', end: '20:40' },
        { periodIndex: 11, start: '20:45', end: '21:30' },
        { periodIndex: 12, start: '21:35', end: '22:20' }
    ];
    // 内置作息表的节数。顺延只往后加，不改上面这 12 条。
    var BUILTIN_PERIODS = 12;
    var PERIOD_MINUTES = 45;
    var PERIOD_BREAK_MINUTES = 10;
    // 时刻的硬上界：载荷的时刻只收 00:00-23:59，越界会让整份载荷被拒。
    var LAST_MINUTE_OF_DAY = 23 * 60 + 59;
    // 节次上限：只为挡住解析跑飞（作息表最远补到第 15 节，第 16 节起没有时刻）。
    var MAX_PERIOD = 20;
    // 周次上限与总周数上限（载荷校验是 1..30）。
    var MAX_WEEK = 30;
    var DEFAULT_TOTAL_WEEKS = 20;
    // 核对提示的硬限制（超了整包被拒，所以自己先截断、先封顶）。
    var MAX_WARNINGS = 20;
    var MAX_WARNING_TEXT = 200;
    // 复合键的分隔符用 NUL，防止「课名 + 教师」拼串撞车。
    // **不写字面 NUL 字节**（那会让 grep 把源文件当二进制），运行时用 fromCharCode 生成。
    var KEY_SEP = String.fromCharCode(0);
    // 名字里只有数字 / 破折号 / 「单双周第节」/ 括号的，是节次残片或分隔符，不是课名
    //（给残片编一门叫「3-4」的课比跳过它更糟；跳过的会进 warnings，不静默）。
    var JUNK_NAME = /^[\s0-9\-—~至,，、;；.。:：()（）单双周第节大]+$/;
    var DAY_CHARS = '一二三四五六日天';
    // 周次文字的特征词：没有它就不当周次解（「见通知」这类不给它编周次）。
    var WEEK_HEAD = /周|单|双/;

    var droppedWeeks = 0;
    var skippedName = 0;
    var skippedWeeks = 0;
    var skippedRows = 0;
    var dirtyRows = 0;
    var unplacedBoxes = 0;
    var mixedMarks = 0;
    var badTimes = 0;
    var dayNote = '';
    var skippedRowsExample = '';
    var skippedWeeksExample = '';
    var warnings = [];
    var warningsDropped = 0;

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

    // 提取时刻所在那一周的周一（firstDayOfWeek = 1，照上游 saveCourseConfig() 声明的口径）。
    // 不用 Date.parse：Rhino 对 ISO 串的支持不齐，自己拆。
    function mondayOf(isoDate) {
        var m = /^(\d{4})-(\d{1,2})-(\d{1,2})/.exec(clean(isoDate));
        if (!m) return '';
        var day = new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]));
        var back = (day.getDay() + 6) % 7; // 周一=1 → 0；周日=0 → 6
        return isoOf(new Date(day.getFullYear(), day.getMonth(), day.getDate() - back));
    }

    function monthOf(isoDate) {
        var m = /^\d{4}-(\d{1,2})/.exec(clean(isoDate));
        return m ? parseInt(m[1], 10) : 0;
    }

    function toMinutes(text) {
        var m = /^(\d{1,2}):(\d{2})$/.exec(clean(text));
        if (!m) return null;
        return parseInt(m[1], 10) * 60 + parseInt(m[2], 10);
    }

    function hhmm(minutes) {
        return pad2(Math.floor(minutes / 60)) + ':' + pad2(minutes % 60);
    }

    function addWarning(text) {
        var value = clean(text);
        if (!value) return;
        if (value.length > MAX_WARNING_TEXT) value = value.substring(0, MAX_WARNING_TEXT - 1) + '…';
        if (warnings.length >= MAX_WARNINGS) {
            warningsDropped++;
            return;
        }
        warnings.push(value);
    }

    // 名字里只有数字/破折号/单双周第节的，不当课名。
    function usableName(value) {
        var text = clean(value);
        if (!text || JUNK_NAME.test(text)) return '';
        return text;
    }

    // 教师：上游写的是 teacherSpan.innerText.replace('教师：','') —— 只认这一个前缀写法。
    // 这里去掉「教师/老师/任课教师…」前缀与冒号，再切掉同一格里的「学分：3」这类尾巴。
    // 取不到就留 null（手册 §4.7：写「未知」会被当成真名显示，空着更好）。
    // 整串就是「学分：3」时也留空 —— 有的格子没有教师 span，取第一个 span 会取到学分那一条，
    // 那不是教师名。
    function teacherOf(raw) {
        var text = clean(raw);
        if (!text) return null;
        text = text.replace(/^(教师|老师|任课教师|授课教师|教师姓名)\s*[:：]?\s*/, '');
        var cut = /学分|学时/.exec(text);
        if (cut) text = cut.index > 0 ? clean(text.substring(0, cut.index)) : '';
        return text ? text : null;
    }

    // 上游把带 item1.png 的那个 div 的 span[0] 当地点、span[1] 当周次（**按位置取**）。
    // 这里按内容取：带「周」且带数字的那个 span 是周次，剩下最靠前的一个是地点；
    // 都不像周次时退回上游的位置口径（第二个 span），仍取不到就交空串 —— 调用方计数进 warnings。
    function spansOf(spans) {
        var list = [];
        var i;
        var raw = spans || [];
        for (i = 0; i < raw.length; i++) list.push(clean(raw[i]));
        var weekIndex = -1;
        for (i = 0; i < list.length; i++) {
            if (/周/.test(list[i]) && /\d/.test(list[i])) {
                weekIndex = i;
                break;
            }
        }
        if (weekIndex < 0) {
            for (i = 0; i < list.length; i++) {
                if (/单|双/.test(list[i]) && /\d/.test(list[i])) {
                    weekIndex = i;
                    break;
                }
            }
        }
        if (weekIndex < 0 && list.length >= 2) weekIndex = 1;
        var location = '';
        for (i = 0; i < list.length; i++) {
            if (i === weekIndex) continue;
            if (list[i]) {
                location = list[i];
                break;
            }
        }
        return { location: location, weeks: weekIndex >= 0 ? list[weekIndex] : '' };
    }

    function pushWeek(out, week, oddOnly, evenOnly) {
        if (week < 1 || week > MAX_WEEK) {
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

    // 周次文本 → 周次数组。上游这一段最薄（/第(.*?)(周|\()/ + 逗号分段），也是本批次最容易
    // 写错的地方，逐条说明：
    //   · 「单」「双」标记在整串任何位置都要认：1-16周(单) / (单)1-16周 / 1-16(单周)；
    //     标记自成一段、用分隔符跟在周次后面的（1-16周,双 / 1-16周、双周 / 1-16周;双）
    //     并入它前面**最近的那一个周次段** —— 这一段落在**末尾**时也一样并进去，不许丢；
    //   · 写在整串最前面的「单周/双周」是**全局修饰**，作用于没带自己标记的分段
    //     （「单周1-9,11-17」）；分段自己带标记时以分段为准；
    //   · 括号里的**纯数字不是周次**（"(1-2)第2-8周" 的 (1-2) 是教学班序号），
    //     所以「先读标记、再把整个括号段抹掉」；
    //   · 方括号里的节次（"1-16周[01-02节]"）、「第1-2节」这类节次写法、以及钟点
    //     （"08:20-09:05"）里的数字都不是周次；
    //   · 只有部分分段带标记的混排写法（"第1-8周,第10-16周(双)"）按字面各段自判，
    //     并计数进 warnings（既不猜、也不静默）；
    //   · 认不出就返回 null，认出来是空的返回空数组 —— 两种都由调用方计数进 warnings。
    function weeksIn(source) {
        var raw = clean(source);
        if (!raw) return null;
        if (!WEEK_HEAD.test(raw)) return null;
        var text = raw.replace(/\[[^\]]*\]/g, ' ');
        // 统一口径第一步：区间分隔符**连同两侧空白**归一成半角连字符 —— 全角波浪
        // ～(U+FF5E)、全角减 －(U+FF0D)、数学减 −(U+2212)、en dash –、em dash —、
        // 半角 ~、汉字「至 / 到」。放在节次/钟点摘除**之前**，下面那几条只认
        // [-—~至] 的正则才能照常吃到全角写法（"第9～10节" 先变 "第9-10节" 再被摘掉）。
        // **不许全文删空白**：空白在本域里也是分段符（"1-3周 5-9周"），全删会并成一段。
        text = text.replace(/\s*[-—–−－~～至到]\s*/g, '-');
        text = text.replace(/第?\s*\d{1,2}\s*[-—~至,，、]\s*\d{1,2}\s*节/g, ' ');
        text = text.replace(/第?\s*\d{1,2}\s*节/g, ' ');
        text = text.replace(/\d{1,2}:\d{2}(\s*[-—~至]\s*\d{1,2}:\d{2})?/g, ' ');

        var globalType = '';
        var head = /^\s*[（(]?\s*(单|双)\s*周?\s*[）)]?/.exec(text);
        if (head) {
            globalType = head[1];
            text = text.substring(head[0].length);
        }
        // 「单双周」是歧义写法：两个标记同时出现时不认全局标记，交给各分段自己判。
        if (/单\s*双|双\s*单/.test(raw)) globalType = '';

        // 统一口径第二步：剩下的空白当分段符（与逗号一类并列），不删
        var segments = text.split(/[\s,，、;；]+/);
        var weeks = [];
        var parsed = [];
        var markedSegments = 0;
        var plainSegments = 0;
        var i;
        // 先只做切分、不动周次：每一段记下「这一段自己的标记」与数字区间；没有数字的是「标记段」。
        for (i = 0; i < segments.length; i++) {
            var segment = segments[i];
            var own = '';
            var mark = /(单|双)/.exec(segment);
            if (mark) own = mark[1];
            if (/单\s*双|双\s*单/.test(segment)) own = '';
            var body = segment.replace(/[（(][^）)]*[）)]/g, ' ')
                .replace(/单|双/g, ' ')
                .replace(/周|第|隔/g, ' ');
            var range = /(\d{1,3})\s*[-—~至]\s*(\d{1,3})/.exec(body);
            var start = null;
            var end = null;
            if (range) {
                start = parseInt(range[1], 10);
                end = parseInt(range[2], 10);
            } else {
                var single = /(\d{1,3})/.exec(body);
                if (single) {
                    start = parseInt(single[1], 10);
                    end = start;
                }
            }
            if (start !== null && start > end) {
                var swap = start;
                start = end;
                end = swap;
            }
            parsed.push({ start: start, end: end, own: own, type: own || globalType });
        }
        // 「标记段」（只有标记、没有数字的那一段）并入**最近的周次段**：前面有周次段就并入
        // 前一段。「第1-16周,双」「第1-16周、双」「第1-16周;双」「第1-16周,双周」这些写法的
        // 标记都跟在周次后面 —— **落在末尾时同样要并入，不许丢**（丢了双周课会静默塌成
        // 每周都上，而且不报警）。前面一段周次都没有时才往后找；整串一段周次都没有的，
        // weeks 是空的，由调用方计数进 warnings，不静默。
        for (var k = 0; k < parsed.length; k++) {
            if (parsed[k].start !== null || !parsed[k].own) continue;
            var target = -1;
            var back;
            for (back = k - 1; back >= 0; back--) {
                if (parsed[back].start !== null) {
                    target = back;
                    break;
                }
            }
            if (target < 0) {
                for (back = k + 1; back < parsed.length; back++) {
                    if (parsed[back].start !== null) {
                        target = back;
                        break;
                    }
                }
            }
            // 这一段自己写了标记的，以它自己写的为准（"第1-8周(单),双" 这种自相矛盾的写法不翻案）
            if (target >= 0 && !parsed[target].type) parsed[target].type = parsed[k].own;
        }
        for (i = 0; i < parsed.length; i++) {
            var item = parsed[i];
            if (item.start === null) continue;
            if (item.type) markedSegments++;
            else if (!globalType) plainSegments++;
            for (var w = item.start; w <= item.end; w++) {
                pushWeek(weeks, w, item.type === '单', item.type === '双');
            }
        }
        if (!globalType && markedSegments > 0 && plainSegments > 0) mixedMarks++;
        return uniqueSorted(weeks);
    }

    // 节次文本 → {start, end}。上游只做 /(\d+)\s*-\s*(\d+)/，两种写法上会出事（见文件头 ③）：
    // 带钟点的格子会先匹配到「20-09」，而「第一」这种中文数字的格子会读不出。这里：
    //   ① 先摘掉钟点；② 认带「节」字的区间/单节；③ 没有「节」字时才退回裸数字（整格就是数字的那种）。
    // 认不出返回 null（调用方按行计数进 warnings）。
    function periodsIn(source) {
        var text = clean(source);
        if (!text) return null;
        // 区间分隔符先归一成半角连字符（与 weeksIn 同一口径）：全角波浪 ～、全角减 －、
        // 数学减 −、en dash –、em dash —、「至 / 到」都当 '-'。不归一的话 "05～06节"
        // 落不进下面的区间正则，会退到「单节」那一条 —— 静默读成只有第 6 节。
        text = text.replace(/\s*[-—–−－~～至到]\s*/g, '-');
        text = text.replace(/\d{1,2}:\d{2}(\s*[-—~至]\s*\d{1,2}:\d{2})?/g, ' ');
        var m = /(\d{1,2})\s*[-—~至]\s*(\d{1,2})\s*节/.exec(text);
        if (m) return bounded(parseInt(m[1], 10), parseInt(m[2], 10));
        m = /(\d{1,2})\s*节/.exec(text);
        if (m) return bounded(parseInt(m[1], 10), parseInt(m[1], 10));
        m = /^\s*第?\s*(\d{1,2})\s*[-—~至]\s*(\d{1,2})\s*$/.exec(text);
        if (m) return bounded(parseInt(m[1], 10), parseInt(m[2], 10));
        m = /^\s*第?\s*(\d{1,2})\s*$/.exec(text);
        if (m) return bounded(parseInt(m[1], 10), parseInt(m[1], 10));
        return null;
    }

    function bounded(start, end) {
        if (!(start >= 1) || !(end >= 1)) return null;
        if (start > end) {
            var swap = start;
            start = end;
            end = swap;
        }
        return { start: start, end: end };
    }

    // 周次集合 → 极大段（移植手册 §4.1）：步长 1 视作每周，步长 2 视作单/双周。
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

    // 连堂合并（上游 mergeCourses() 的口径）：同一门课、同一天、同一段周次、同一地点，
    // 且节次相接或重叠 → 合成一块。上游要求 weeks 数组逐字相等，这里要求切出来的段相同。
    function mergeBlocks(blocks) {
        var groups = [];
        var index = {};
        var i;
        for (i = 0; i < blocks.length; i++) {
            var b = blocks[i];
            var key = [b.dayOfWeek, b.startWeek, b.endWeek, b.weekType, b.location || ''].join('|');
            if (index[key] === undefined) {
                index[key] = groups.length;
                groups.push([]);
            }
            groups[index[key]].push(b);
        }
        var out = [];
        for (i = 0; i < groups.length; i++) {
            var list = groups[i];
            list.sort(function (a, b) { return a.startPeriod - b.startPeriod; });
            var current = list[0];
            for (var j = 1; j < list.length; j++) {
                if (list[j].startPeriod <= current.endPeriod + 1) {
                    if (list[j].endPeriod > current.endPeriod) current.endPeriod = list[j].endPeriod;
                } else {
                    out.push(current);
                    current = list[j];
                }
            }
            out.push(current);
        }
        return out;
    }

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

    // 学年学期名兜底：上游没有学期概念（它只让用户输学年 + 第几学期来拼 xnxqid），
    // 教务页面也读不到学期名时按提取日期推：8 月及以后算本学年第一学期，2-7 月算第二学期。
    function termNameFromDate(isoDate) {
        var m = /^(\d{4})-(\d{1,2})/.exec(clean(isoDate));
        if (!m) return '';
        var year = parseInt(m[1], 10);
        var month = parseInt(m[2], 10);
        if (month >= 8) return year + '-' + (year + 1) + '学年第一学期';
        if (month === 1) return (year - 1) + '-' + year + '学年第一学期';
        return (year - 1) + '-' + year + '学年第二学期';
    }

    // 总周数：上游读 #li_showWeek 的「/20周」。extract.js 把那段原始文字交出来，这里解。
    function totalWeeksHint(text) {
        var value = clean(text);
        if (!value) return null;
        var m = /\/\s*(\d{1,2})\s*周/.exec(value);
        if (!m) m = /(\d{1,2})\s*周/.exec(value);
        if (!m) return null;
        var weeks = parseInt(m[1], 10);
        return weeks >= 1 && weeks <= MAX_WEEK ? weeks : null;
    }

    // ── ① 作息表：季节按提取日期判，再按课表里出现的节次顺延补出 ──
    var firstDay = mondayOf(data.now);
    if (!firstDay) throw new Error('提取数据里缺少 now（提取时刻），无法推算开学日期');
    var now = clean(data.now);
    var month = monthOf(now);
    var summer = month >= 5 && month <= 9;
    var periodTimes = [];
    var source = summer ? SUMMER_PERIODS : WINTER_PERIODS;
    for (var pi = 0; pi < source.length; pi++) {
        periodTimes.push({
            periodIndex: source[pi].periodIndex,
            start: source[pi].start,
            end: source[pi].end
        });
    }

    // ── ② 表头：找「没有课块、且有 ≥4 个星期标签」的那一行，按它的**列号**取每天那一列 ──
    var headerRow = -1;
    var bestLabels = 0;
    var headerDayCol = [0, -1, -1, -1, -1, -1, -1, -1];
    var r;
    var c;
    for (r = 0; r < rows.length; r++) {
        var headerCells = rows[r].cells || [];
        var headerHasBox = false;
        var cols = [0, -1, -1, -1, -1, -1, -1, -1];
        var labels = 0;
        for (c = 0; c < headerCells.length; c++) {
            if (headerCells[c].boxes && headerCells[c].boxes.length) headerHasBox = true;
            var day = dayOfLabel(headerCells[c].text);
            if (day >= 1 && day <= 7 && cols[day] < 0) {
                cols[day] = headerCells[c].col;
                labels++;
            }
        }
        if (headerHasBox || labels < 4) continue;
        if (labels > bestLabels) {
            bestLabels = labels;
            headerRow = r;
            headerDayCol = cols;
        }
    }

    // 网格列号 → 星期几。表头认得出就用表头；没有表头时按网格列号兜底：
    // 有课块的行里出现过的列号，去掉最左边那一列（节次列）之后正好 7 个，就按从左到右
    // 对应周一到周日（**不按数组长度猜列**，这是本批次检查表第 3 条）。认不出就不产课块、进 warnings。
    var colToDay = {};
    var dayCount = 0;
    var labelCol = -1;
    var d;
    if (bestLabels >= 4) {
        for (d = 1; d <= 7; d++) {
            if (headerDayCol[d] >= 0) {
                colToDay[headerDayCol[d]] = d;
                dayCount++;
            }
        }
    } else {
        var present = {};
        var boxRows = 0;
        for (r = 0; r < rows.length; r++) {
            var probed = rows[r].cells || [];
            var rowHasBox = false;
            for (c = 0; c < probed.length; c++) {
                if (probed[c].boxes && probed[c].boxes.length) rowHasBox = true;
            }
            if (!rowHasBox) continue;
            boxRows++;
            for (c = 0; c < probed.length; c++) present[probed[c].col] = true;
        }
        var presentCols = [];
        for (var presentKey in present) presentCols.push(parseInt(presentKey, 10));
        presentCols.sort(function (a, b) { return a - b; });
        labelCol = presentCols.length ? presentCols[0] : -1;
        var rest = [];
        for (var rc = 0; rc < presentCols.length; rc++) {
            if (presentCols[rc] !== labelCol) rest.push(presentCols[rc]);
        }
        if (rest.length === 7) {
            for (d = 1; d <= 7; d++) colToDay[rest[d - 1]] = d;
            dayCount = 7;
            dayNote = '课表里没找到星期表头，已按除节次列外的 7 个网格列从左到右对应周一到周日，请核对导入结果';
        } else {
            dayNote = '课表里没找到星期表头，除节次列外的网格列有 ' + rest.length +
                ' 个（不是 7 个），无法定位星期，已跳过这些课块';
        }
    }

    // rowSpan 覆盖：表格的节次格常见 rowspan=2（「第一大节」跨两行）。某一行没有那一格时，
    // 沿用上方 rowspan 覆盖到本行的那一格 —— 这是表格自己的结构，不是猜。
    var carry = {};
    function frameOf(row) {
        var cells = (row && row.cells) || [];
        var byCol = {};
        var i;
        for (i = 0; i < cells.length; i++) byCol[cells[i].col] = cells[i];
        var covered = {};
        var key;
        for (key in carry) {
            if (carry[key] && carry[key].left > 0 && !byCol[key]) covered[key] = carry[key];
        }
        // 本行收尾：先把上一轮留下的 rowSpan 计数减一，再登记本行新产生的
        for (key in carry) {
            if (!carry[key]) continue;
            carry[key].left--;
            if (carry[key].left <= 0) delete carry[key];
        }
        for (i = 0; i < cells.length; i++) {
            if (cells[i].rowSpan > 1) carry[cells[i].col] = { text: cells[i].text, left: cells[i].rowSpan - 1 };
        }
        return { byCol: byCol, covered: covered };
    }

    function colsOf(frame) {
        var list = [];
        var key;
        for (key in frame.byCol) list.push(parseInt(key, 10));
        for (key in frame.covered) {
            if (!frame.byCol[key]) list.push(parseInt(key, 10));
        }
        list.sort(function (a, b) { return a - b; });
        return list;
    }

    function cellOf(frame, col) {
        return frame.byCol[col] || frame.covered[col] || null;
    }

    function hasBoxes(cell) {
        return !!(cell && cell.boxes && cell.boxes.length);
    }

    // ── ③ 逐行逐格取课 ──
    var order = [];
    var byCourse = {};
    var maxPeriod = 0;
    for (var ri = 0; ri < rows.length; ri++) {
        var frame = frameOf(rows[ri]);
        if (ri === headerRow) continue;
        var colsHere = colsOf(frame);
        // 节次格：最靠左的「没有课块」的那一格（都被课块占了就取最靠左的那一格）
        var rowLabelCol = -1;
        var labelText = '';
        for (var li = 0; li < colsHere.length; li++) {
            if (!hasBoxes(cellOf(frame, colsHere[li]))) {
                rowLabelCol = colsHere[li];
                labelText = cellOf(frame, colsHere[li]).text;
                break;
            }
        }
        if (rowLabelCol < 0 && colsHere.length) {
            rowLabelCol = colsHere[0];
            labelText = cellOf(frame, colsHere[0]).text;
        }
        var rowHasBoxes = false;
        for (var bi = 0; bi < colsHere.length; bi++) {
            if (hasBoxes(cellOf(frame, colsHere[bi]))) rowHasBoxes = true;
        }
        var periods = periodsIn(labelText);
        if (!periods) {
            if (rowHasBoxes) {
                skippedRows++;
                if (!skippedRowsExample) skippedRowsExample = clean(labelText);
            }
            continue;
        }
        if (periods.start > MAX_PERIOD || periods.end > MAX_PERIOD) {
            if (rowHasBoxes) dirtyRows++;
            continue;
        }
        for (var ci = 0; ci < colsHere.length; ci++) {
            var col = colsHere[ci];
            var cell = cellOf(frame, col);
            if (!hasBoxes(cell)) continue;
            var day = colToDay[col];
            if (!day) {
                // 有课、但这一列对不上星期（表头只列了 5 天、或列数不是 7）：计数进 warnings，不猜
                if (col !== rowLabelCol) unplacedBoxes += cell.boxes.length;
                continue;
            }
            for (var b = 0; b < cell.boxes.length; b++) {
                var box = cell.boxes[b] || {};
                var name = usableName(box.name);
                if (!name) {
                    skippedName++;
                    continue;
                }
                var spec = spansOf(box.spans);
                var weeks = weeksIn(spec.weeks);
                if (!weeks || !weeks.length) {
                    skippedWeeks++;
                    if (!skippedWeeksExample) skippedWeeksExample = spec.weeks ? spec.weeks : clean(box.name);
                    continue;
                }
                var teacher = teacherOf(box.teacher);
                // 课程要等真产出一条安排才登记：周次解析不出来的块只进 warnings，
                // 不能在课表里留下一门「没有任何安排」的空课。
                var courseKey = name + KEY_SEP + (teacher || '');
                if (!byCourse[courseKey]) {
                    byCourse[courseKey] = { name: name, teacher: teacher, note: null, blocks: [], seen: {} };
                    order.push(courseKey);
                }
                var course = byCourse[courseKey];
                var runs = runsOf(weeks);
                for (var n = 0; n < runs.length; n++) {
                    var run = runs[n];
                    var block = {
                        dayOfWeek: day,
                        startPeriod: periods.start,
                        endPeriod: periods.end,
                        startWeek: run.start,
                        endWeek: run.end,
                        weekType: run.weekType,
                        location: spec.location ? spec.location : null
                    };
                    var blockKey = [block.dayOfWeek, block.startPeriod, block.endPeriod,
                        block.startWeek, block.endWeek, block.weekType, block.location || ''].join('|');
                    if (course.seen[blockKey]) continue;
                    course.seen[blockKey] = true;
                    course.blocks.push(block);
                }
            }
        }
        if (periods.end > maxPeriod) maxPeriod = periods.end;
    }

    if (order.length === 0) {
        var reasons = [];
        if (dayCount === 0) reasons.push('没能定位星期');
        if (skippedRows > 0) reasons.push(skippedRows + ' 行的节次读不出来');
        if (skippedWeeks > 0) reasons.push(skippedWeeks + ' 个课程块的周次读不出来');
        if (unplacedBoxes > 0) reasons.push(unplacedBoxes + ' 个课程块落在认不出的列上');
        if (dirtyRows > 0) reasons.push(dirtyRows + ' 行的节次超出 ' + MAX_PERIOD + ' 节');
        throw new Error('本学期没有解析到任何课程（' + (reasons.length ? reasons.join('；') : '课表是空的') +
            '）：可能是还没排课，或者登录状态已失效、教务页面结构改了。请在教务里打开课表页后重试');
    }

    // ── ④ 作息表顺延：覆盖到课表里出现的每一节，且补出来的时刻必须合法 ──
    var extended = 0;
    var clamped = '';
    var known = periodTimes.length;
    while (known < maxPeriod) {
        var prevEnd = toMinutes(periodTimes[known - 1].end);
        if (prevEnd === null) break;
        var nextStart = prevEnd + PERIOD_BREAK_MINUTES;
        if (nextStart >= LAST_MINUTE_OF_DAY) break; // 连开始时刻都越界，这一节及以后都补不出
        var nextEnd = nextStart + PERIOD_MINUTES;
        if (nextEnd > LAST_MINUTE_OF_DAY) {
            nextEnd = LAST_MINUTE_OF_DAY; // 截断到当天最后一刻（写出 24:10 会让整份载荷被拒）
            clamped = hhmm(nextStart) + '-' + hhmm(nextEnd);
        }
        periodTimes.push({ periodIndex: known + 1, start: hhmm(nextStart), end: hhmm(nextEnd) });
        known++;
        extended++;
        if (clamped) break;
    }
    // 最后一道闸：任何一条不合法（格式、先后）的节次都不许出现在载荷里 —— 载荷的时间校验
    // 只收 00:00-23:59，一条非法的会让**整次导入失败**（不是只丢这一条）。
    var legalPeriods = [];
    var lastTimed = 0;
    for (var lp = 0; lp < periodTimes.length; lp++) {
        var startMin = toMinutes(periodTimes[lp].start);
        var endMin = toMinutes(periodTimes[lp].end);
        if (startMin === null || endMin === null || startMin >= endMin) {
            badTimes++;
            continue;
        }
        legalPeriods.push(periodTimes[lp]);
        if (periodTimes[lp].periodIndex > lastTimed) lastTimed = periodTimes[lp].periodIndex;
    }
    periodTimes = legalPeriods;

    var untimedBlocks = 0;
    var maxWeek = 0;
    var courses = [];
    for (var oi = 0; oi < order.length; oi++) {
        var item = byCourse[order[oi]];
        item.blocks = mergeBlocks(item.blocks);
        for (var bj = 0; bj < item.blocks.length; bj++) {
            if (item.blocks[bj].endWeek > maxWeek) maxWeek = item.blocks[bj].endWeek;
            if (item.blocks[bj].endPeriod > lastTimed) untimedBlocks++;
        }
        courses.push({ name: item.name, teacher: item.teacher, note: item.note, blocks: item.blocks });
    }

    // 总周数：上游读 #li_showWeek（页面给的就是 20 这个量级），课表里出现更晚的周次时按它抬高
    // 并进 warnings —— 抬高了不说，用户就没机会发现「学期总周数不对」。
    var hint = totalWeeksHint(data.totalWeeksText);
    var totalWeeks = hint === null ? DEFAULT_TOTAL_WEEKS : hint;
    if (maxWeek > totalWeeks) totalWeeks = maxWeek;
    if (totalWeeks > MAX_WEEK) totalWeeks = MAX_WEEK;

    var termName = clean(term.name) || termNameFromCode(term.code);
    var termInferred = '';
    if (!termName) {
        termInferred = termNameFromDate(now);
        termName = termInferred ? termInferred : '湖南商务职业技术学院课表';
    }

    // ── ⑤ 推算/假定出来的东西逐条说清楚（手册 §4.2）──
    // 顺序是固定的：先「哪些字段是推算的」，再「结构上认出了什么」，最后「哪些块被丢掉了」。
    addWarning('开学日期无法从教务获取（课表页不给开学日），已按提取当天所在周的周一 ' + firstDay +
        ' 推算，请在「学期管理」里核对');
    addWarning('作息时间取自适配器内置的作息表（' + (summer ? '夏季' : '冬季') + '作息 ' + BUILTIN_PERIODS +
        ' 节）：教务页面取不到上下课时间，季节按提取日期 ' + now + (summer ? ' 落在 5-9 月判定为夏季' : ' 不在 5-9 月判定为冬季') +
        '，请在「学期管理」里核对');
    if (termInferred) {
        addWarning('学期名无法从教务页面获取，已按提取日期推算为「' + termInferred + '」，请在「学期管理」里改名');
    }
    if (hint === null) {
        addWarning('教务页面上没读到学期总周数，已按 ' + DEFAULT_TOTAL_WEEKS + ' 周设定，请在「学期管理」里核对');
    } else if (maxWeek > hint) {
        addWarning('课表里出现了第 ' + maxWeek + ' 周的课，学期总周数已从教务标的 ' + hint + ' 周抬高到 ' +
            totalWeeks + ' 周，请在「学期管理」里核对');
    }
    addWarning(dayNote);
    if (unplacedBoxes > 0) {
        addWarning('有 ' + unplacedBoxes + ' 个课程块落在认不出星期的列上（表头没列那一天），已跳过，请核对');
    }
    if (skippedRows > 0) {
        addWarning('有 ' + skippedRows + ' 行的节次读不出来（例如「' + skippedRowsExample + '」），已跳过这些行的课程');
    }
    if (dirtyRows > 0) {
        addWarning('有 ' + dirtyRows + ' 行的节次超出第 ' + MAX_PERIOD + ' 节，已按脏数据跳过');
    }
    if (skippedWeeks > 0) {
        addWarning('有 ' + skippedWeeks + ' 个课程块没能解析出周次（例如「' + skippedWeeksExample + '」），已跳过');
    }
    if (skippedName > 0) {
        addWarning('有 ' + skippedName + ' 个课程块没能认出课程名，已跳过');
    }
    if (droppedWeeks > 0) {
        addWarning('有 ' + droppedWeeks + ' 个周次超出 1-' + MAX_WEEK + ' 的范围，已按脏数据丢弃');
    }
    if (extended > 0) {
        addWarning('课表里出现了第 ' + maxPeriod + ' 节：内置作息表只到第 ' + BUILTIN_PERIODS + ' 节，第 ' +
            (BUILTIN_PERIODS + 1) + ' 节起已按每节 ' + PERIOD_MINUTES + ' 分钟、课间 ' + PERIOD_BREAK_MINUTES +
            ' 分钟顺延补出（补到第 ' + lastTimed + ' 节），请核对');
    }
    if (clamped) {
        addWarning('第 ' + lastTimed + ' 节的顺延下课时间会超过 23:59，已截断到 23:59' +
            '（载荷的时刻只收 00:00-23:59，写出 24:10 会让整次导入失败）');
    }
    if (badTimes > 0) {
        addWarning('有 ' + badTimes + ' 个节次的作息时间不合法，已从作息表里去掉（载荷只收 00:00-23:59 的时刻）');
    }
    if (untimedBlocks > 0) {
        addWarning('有 ' + untimedBlocks + ' 个课程块用到了第 ' + (lastTimed + 1) + ' 节及以后：再往后顺延就超过 23:59，' +
            '作息表补不出这些节次的上下课时间。课块已保留，但没有时刻、课表上也画不出来，请核对');
    }
    if (mixedMarks > 0) {
        addWarning('有 ' + mixedMarks + ' 个课程块的周次里只有部分分段带「单/双」标记，' +
            '已按字面各段自判（例如「第1-8周,第10-16周(双)」按前半每周、后半双周处理），请核对');
    }
    if (warningsDropped > 0) {
        warnings[MAX_WARNINGS - 1] = '另有 ' + warningsDropped + ' 条说明未显示（核对提示上限 ' +
            MAX_WARNINGS + ' 条）';
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
