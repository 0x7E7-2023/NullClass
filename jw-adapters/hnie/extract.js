(function () {
    // 湖南工程学院 教务适配器 —— 强智科技「高校综合管理教务系统」（学生端 /jsxsd/）。
    // 移植自 shiguang_warehouse 的 HNIE/hnie_01.js
    //   https://github.com/XingHeYuZhuan/shiguang_warehouse  （MIT，上游作者 Mercury）
    // 同目录 adapters.yaml：adapter_name「湖南工程学院」，maintainer「Mercury」，
    //   import_url = http://jwcmis.hnie.edu.cn
    //
    // 平台判据按脚本**实际请求的接口路径**，不按注释：
    //   POST /jsxsd/xskb/xskb_list.do   强智学生端课表页（返回服务端渲染的 HTML，不是 JSON）
    //   POST /jsxsd/jxzl/jxzl_query     强智学生端教学周历（开学日期在这一页的 td[title] 上）
    //   容器 id #timetable（课表）/ #kbtable（周历）、div.kbcontent、font[title=教师|教室|周次(节次)]
    // 都是强智的写法（正方新版走 /jwglxt/…xskbcx_cxXsKb.html，两回事）。
    //
    // 取数方式：DOM 抓取，两级兜底：
    //   ① 当前页面就有课表表格 → 直接用（用户此刻看到的那张，也不多发请求）；
    //   ② POST /jsxsd/xskb/xskb_list.do（请求体与上游一字不差）。
    // 无论走哪条，都再 POST 一次 /jsxsd/jxzl/jxzl_query 取教学周历 —— 那是本适配器**唯一**
    // 能拿到真实开学日期的来源（拿不到才由 parse.js 推算，并写进 warnings）。
    //
    // 移植改动：
    //   ① 不再弹窗问「起始学年 / 第一学期还是第二学期」（上游让用户手输年份、再手选学期，
    //      拼出 xnxq01id）：学期从课表页的「学年学期」下拉框取**选中项** ——
    //      用户在页面上切到哪一学期，导入的就是哪一学期；
    //   ② 作息（冬令时 / 夏令时）**仍然要问**：上游就是这么设计的，而且这两种作息下午与
    //      晚上的节次时间差 30-40 分钟，页面上没有这个信息。改问一次 __ncSelect；
    //      桥不可用或用户取消时按学期推，并由 parse.js 在 warnings 里说明；
    //   ③ 只交原始结构出去（学期 + 每格的网格列号 col / 跨列数 span / 原始 HTML + 周历的网格），
    //      周次 / 节次 / 课程名的解释全在 parse.js —— 那边 CI 里能真跑；
    //   ④ 去掉上游的 showAlert / showToast / notifyTaskCompletion 与那个写进页面的
    //      window.validateYearInput 全局函数（空课的导入流程自己会确认，脚本对页面只读）；
    //   ⑤ 上游把 div.kbcontent 与 div.kbcontent1 两份明细**都**当课程读（同一门课会被算两次），
    //      这里只取一份（优先 display:none 的那份完整版）。
    //
    // 只请求当前页面同源的一台教务服务器（jwcmis.hnie.edu.cn）的相对路径：不读账号密码、
    // 不写页面、不外发任何数据。详见 AUDIT.md。
    var SCHEDULE_PATH = '/jsxsd/xskb/xskb_list.do';
    var CALENDAR_PATH = '/jsxsd/jxzl/jxzl_query';
    // 教学周历一次问 20 周（上游写死的循环上界）
    var CALENDAR_WEEKS = 20;
    var LOGIN_HINT = '请先在页面里登录教务系统并打开「课表查询」，确认能看到课表再点提取';
    var CN_DATE = /\d{4}\s*年\s*\d{1,2}\s*月\s*\d{1,2}\s*日/;

    function clean(value) {
        if (value === null || value === undefined) return '';
        return String(value).replace(/\s+/g, ' ').trim();
    }

    function pad2(n) {
        return (n < 10 ? '0' : '') + n;
    }

    // 提取时刻（本地日期）。教务给不出开学日时由 parse.js 用它推算第 1 周。
    // 不能用 toISOString：北京时间早上 8 点前它会退到前一天。
    function todayIso() {
        var now = new Date();
        return now.getFullYear() + '-' + pad2(now.getMonth() + 1) + '-' + pad2(now.getDate());
    }

    function tagOf(node) {
        return String((node && node.tagName) || '').toUpperCase();
    }

    function attrOf(node, name) {
        return node && node.getAttribute ? String(node.getAttribute(name) || '') : '';
    }

    // 一格里的「明细」：强智把课程名 + 教师 / 周次(节次) / 教室一起放在 class 含 kbcontent 的
    // div 里，旁边那份可见的 div 是同一门课的简写。上游把 kbcontent 与 kbcontent1 **都**读，
    // 同一门课会被算两次；这里先取带 display:none 的那份（完整版），没有隐藏的就取第一份 ——
    // 只取一份。
    function partsOf(cell) {
        var divs = cell.getElementsByTagName('div');
        var hidden = [];
        var any = [];
        for (var i = 0; i < divs.length; i++) {
            if (String(divs[i].className || '').indexOf('kbcontent') < 0) continue;
            any.push(divs[i]);
            if (attrOf(divs[i], 'style').indexOf('none') >= 0) hidden.push(divs[i]);
        }
        var picked = hidden.length ? hidden : (any.length ? [any[0]] : []);
        var parts = [];
        for (var j = 0; j < picked.length; j++) {
            var html = String(picked[j].innerHTML || '').trim();
            if (html && html !== '&nbsp;') parts.push(html);
        }
        return parts;
    }

    function cellOf(node, col, span, withNode) {
        var text = node.textContent !== undefined ? node.textContent : node.innerText;
        var cell = { col: col, span: span, text: clean(text), parts: partsOf(node) };
        // withNode 只给周历用：它要读 td[title] 上的日期。课表那边不用，免得每个格子里
        // 都多一个空字段（原始 HTML 已经够长了）。
        if (withNode) cell.node = node;
        return cell;
    }

    function spanOfAttr(node, name) {
        var value = parseInt(attrOf(node, name), 10);
        return value >= 1 ? value : 1;
    }

    // carry[c] = 第 c 列还被前面某一行的 rowspan 占着几行（不含当前行）。
    // 每进一行先把占用的列标出来并把计数减 1；rowspan=2 的格子只在它自己那一行留下 td，
    // 它占住的列在下一行必须空出来，否则下一行的格子会整体左移一格 —— 课表的首列是节次列，
    // 它常被 rowspan 跨两行，正是靠 col 才对齐得回来（见 parse.js 的列映射）。
    function cellsOfRow(row, carry, withNode) {
        var out = [];
        var kids = row.children || [];
        var occupied = {};
        for (var k = 0; k < carry.length; k++) {
            if (carry[k] > 0) {
                occupied[k] = true;
                carry[k] = carry[k] - 1;
            }
        }
        var col = 0;
        for (var i = 0; i < kids.length; i++) {
            var tag = tagOf(kids[i]);
            if (tag !== 'TD' && tag !== 'TH') continue;
            while (occupied[col]) col++;
            var span = spanOfAttr(kids[i], 'colspan');
            var rowspan = spanOfAttr(kids[i], 'rowspan');
            out.push(cellOf(kids[i], col, span, withNode));
            if (rowspan > 1) {
                for (var s = 0; s < span; s++) carry[col + s] = rowspan - 1;
            }
            col += span;
        }
        return out;
    }

    function closestTable(el) {
        var node = el;
        while (node && node.nodeType === 1) {
            if (tagOf(node) === 'TABLE') return node;
            node = node.parentNode;
        }
        return null;
    }

    // 课表容器：上游写死 #timetable（**周历**表是 #kbtable，两者别混）。认不出 #timetable
    // 就退一步找「最近一张含 .kbcontent 格子的表」—— 同平台的其它强智部署见过课表用
    // #kbtable 的，那样也能靠这条兜底认出来，而周历表里没有 .kbcontent，不会被误认。
    function findScheduleTable(doc) {
        var byId = doc.getElementById('timetable');
        if (byId) return byId;
        var cell = doc.querySelector ? doc.querySelector('.kbcontent') : null;
        return cell ? closestTable(cell) : null;
    }

    // 课表：整张表还原成网格，每个格子带上**网格列号** col 与跨列数 span，表宽一起交出去。
    // 不能让 parse.js 拿「第几个 td」当星期几：首列是节次列、还有 rowspan/colspan。
    // 只交「有文字或有课程明细」的行（节次标签行照交，parse.js 靠它认无表头时的对齐）。
    function scheduleGrid(table) {
        var out = [];
        var width = 0;
        var trs = table.rows || table.getElementsByTagName('tr');
        var carry = [];
        for (var r = 0; r < trs.length; r++) {
            // carry 要跨行走：哪怕这一行最后不交出去，也要过一遍，否则后面的列号会错
            var cells = cellsOfRow(trs[r], carry, false);
            for (var w = 0; w < cells.length; w++) {
                if (cells[w].col + cells[w].span > width) width = cells[w].col + cells[w].span;
            }
            var keep = false;
            for (var c = 0; c < cells.length; c++) {
                if (cells[c].text || cells[c].parts.length) {
                    keep = true;
                    break;
                }
            }
            if (keep) out.push(cells);
        }
        return { cols: width, rows: out };
    }

    // 教学周历的容器：上游写死 #kbtable。认不出就找第一张有「2026年09月07日」这种 title 的表
    //（周历的日期挂在 td[title] 上）。
    function findCalendarTable(doc) {
        var byId = doc.getElementById('kbtable');
        if (byId) return byId;
        var cells = doc.getElementsByTagName('td');
        for (var i = 0; i < cells.length; i++) {
            if (CN_DATE.test(attrOf(cells[i], 'title'))) return closestTable(cells[i]);
        }
        return null;
    }

    // 周历：只交「有文字或有 title」的格子（空白的日子格没有信息），但保留网格列号 ——
    // parse.js 要靠列号认「星期几在哪一列」，再读第 1 周那一行。
    function calendarGrid(table) {
        var out = [];
        var trs = table.rows || table.getElementsByTagName('tr');
        var carry = [];
        for (var r = 0; r < trs.length; r++) {
            var cells = cellsOfRow(trs[r], carry, true);
            var keep = [];
            for (var c = 0; c < cells.length; c++) {
                var item = { col: cells[c].col };
                if (cells[c].text) item.text = cells[c].text;
                var title = clean(attrOf(cells[c].node, 'title'));
                if (title) item.title = title;
                if (item.text || item.title) keep.push(item);
            }
            if (keep.length) out.push(keep);
        }
        return out;
    }

    // 有课内容的格子数：判断这一页算不算「拿到了课表」
    function courseCells(rows) {
        var count = 0;
        for (var r = 0; r < rows.length; r++) {
            for (var c = 0; c < rows[r].length; c++) {
                if (rows[r][c].parts.length) count++;
            }
        }
        return count;
    }

    // 学年学期：课表页顶部的下拉框（强智是 select[id|name 含 xnxq]，值形如 2026-2027-1）。
    // 只读**选中项**：用户在页面上切到哪一学期，导入的就是哪一学期。
    // 这里是本适配器读取页面的**唯一**入口（详见 AUDIT.md「读取面」一节）：不读别的元素文字、
    // 不读整页、不碰任何与课表无关的内容。
    function readTerm(doc) {
        var code = null;
        var name = null;
        var selects = doc.getElementsByTagName('select');
        for (var i = 0; i < selects.length && !code; i++) {
            var identity = String(selects[i].id || '') + ' ' + String(selects[i].name || '');
            if (identity.indexOf('xnxq') < 0) continue;
            var options = selects[i].options || [];
            var firstCode = null;
            var firstName = null;
            for (var j = 0; j < options.length; j++) {
                var value = clean(options[j].value);
                if (!/^\d{4}-\d{4}-\d$/.test(value)) continue;
                if (firstCode === null) {
                    firstCode = value;
                    firstName = clean(options[j].text);
                }
                if (options[j].selected) {
                    code = value;
                    name = clean(options[j].text);
                }
            }
            if (!code && firstCode) {
                code = firstCode;
                name = firstName;
            }
        }
        return { code: code, name: name };
    }

    function harvestPage(doc) {
        var table = findScheduleTable(doc);
        var grid = table ? scheduleGrid(table) : { cols: 0, rows: [] };
        return { term: readTerm(doc), cols: grid.cols, rows: grid.rows };
    }

    function htmlDoc(html) {
        return new DOMParser().parseFromString(html, 'text/html');
    }

    function requestText(url, body) {
        return fetch(url, {
            method: 'POST',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
            body: body,
            credentials: 'same-origin'
        }).then(function (response) {
            if (response.status === 401 || response.status === 403) {
                throw new Error('教务系统拒绝访问（' + response.status + '）：' + LOGIN_HINT);
            }
            if (response.status < 200 || response.status >= 300) {
                throw new Error('教务系统返回 HTTP ' + response.status + '：' + LOGIN_HINT);
            }
            return response.text();
        }, function () {
            throw new Error('连不上教务系统：登录状态可能已失效。' + LOGIN_HINT);
        });
    }

    // 课表页的请求体与上游一字不差（sfFD / wkbkc / 那几个空参数都留着）
    function scheduleBody(code) {
        return 'cj0701id=&zc=&demo=&xnxq01id=' + encodeURIComponent(code || '') + '&sfFD=1&wkbkc=1';
    }

    // 教学周历的请求体同上：xnxq01id + 20 组重复的 xqt（1..20 各两次）
    function calendarBody(code) {
        var body = 'xnxq01id=' + encodeURIComponent(code || '');
        for (var i = 1; i <= CALENDAR_WEEKS; i++) body += '&xqt=' + i + '&xqt=' + i;
        return body;
    }

    // 周历取不到（教务改了页面、或这一学期没有周历）不算致命：开学日期退回推算，
    // 由 parse.js 在 warnings 里如实说明。这里返回空网格而不是抛错。
    function fetchCalendar(code) {
        return requestText(CALENDAR_PATH, calendarBody(code)).then(function (html) {
            var table = findCalendarTable(htmlDoc(html));
            var rows = table ? calendarGrid(table) : [];
            return { found: rows.length > 0, rows: rows };
        }, function () {
            return { found: false, rows: [] };
        });
    }

    // 作息（冬令时 / 夏令时）：上游是让用户选。空课的提问桥就是为这种「页面上没有、又必须
    // 用户定」的值准备的，问一次；桥不可用或用户取消时交 null，由 parse.js 推断并出声。
    // 取消是正常结果（resolve null），不是错误 —— 只有参数不合法 / 桥坏了才 reject。
    function pickSeason() {
        var caps = typeof __ncCapabilities === 'object' && __ncCapabilities ? __ncCapabilities : {};
        if (!caps.ask || typeof __ncSelect !== 'function') {
            return Promise.resolve({ asked: false, picked: null });
        }
        return __ncSelect({
            title: '选择作息时间',
            message: '湖南工程学院分冬令时与夏令时两套作息（下午与晚上的上课时间不同）。' +
                '请选择本学期实际使用的作息，导入后也可以在「学期管理」里改。',
            items: ['冬令时', '夏令时'],
            defaultIndex: 0
        }).then(function (index) {
            if (index === 0) return { asked: true, picked: 'winter' };
            if (index === 1) return { asked: true, picked: 'summer' };
            return { asked: true, picked: null };
        }, function () {
            return { asked: false, picked: null };
        });
    }

    var live = harvestPage(document);

    function payloadOf(flight, season, calendar) {
        return JSON.stringify({
            source: 'qz-jsxsd',
            pageUrl: String(window.location.href),
            now: todayIso(),
            term: flight.term,
            season: season,
            cols: flight.cols,
            calendar: calendar,
            rows: flight.rows
        });
    }

    return pickSeason().then(function (season) {
        return fetchCalendar(live.term.code).then(function (calendar) {
            if (courseCells(live.rows) > 0) return payloadOf(live, season, calendar);
            return requestText(SCHEDULE_PATH, scheduleBody(live.term.code)).then(function (html) {
                var got = harvestPage(htmlDoc(html));
                if (courseCells(got.rows) === 0) {
                    throw new Error(
                        '教务返回的课表是空的：可能本学期还没排课，或者登录状态已失效。' + LOGIN_HINT
                    );
                }
                // 当前页不在课表页时学年学期下拉框通常不在，那就用课表页自己那份
                var term = (got.term && got.term.code) ? got.term : live.term;
                return payloadOf({ term: term, cols: got.cols, rows: got.rows }, season, calendar);
            });
        });
    });
})()
