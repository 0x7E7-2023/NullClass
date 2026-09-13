(function () {
    // 南昌航空大学科技学院 教务适配器 —— 强智科技「高校综合管理教务系统」（学生端 /jsxsd/）。
    // 移植自 shiguang_warehouse 的 STCNCHU/stcnchu.js
    //   https://github.com/XingHeYuZhuan/shiguang_warehouse  （MIT，上游作者 星河欲转）
    // 同目录 adapters.yaml：adapter_name「南昌航空大学科技学院强智教务」，
    //   maintainer「星河欲转」，import_url = http://qzjwxt.stcnchu.edu.cn:800/jsxsd/
    //
    // 取数方式：强智课表页返回服务端渲染的 HTML（不是 JSON），所以是 DOM 抓取。两级：
    //   ① 当前页面就有课表表格（#kbtable）→ 直接用（用户此刻看到的那张，也不多发请求）
    //   ② 同源 POST <当前页面 origin>/jsxsd/xskb/xskb_list.do —— 与上游同一个接口、
    //      同一份表单体（jx0404id / cj0701id / zc / demo / xnxq01id），学期号取自
    //      页面自己的「学年学期」下拉框（上游是让用户手输学年再手选学期拼出来）
    //
    // 关于请求的域（本件的重点，见 AUDIT.md §1）：
    //   这台教务是**非标准端口 :800**（http://qzjwxt.stcnchu.edu.cn:800/jsxsd/）。
    //   这里**不写死任何主机名与端口**，只请求**当前页面同源**的相对路径 ——
    //   端口是 origin 的一部分，同源判定与 allowHosts 都按主机名走；
    //   把 "http://qzjwxt.stcnchu.edu.cn:800/..." 写死进脚本，等于绕开同源这条线。
    //
    // 移植改动：
    //   ① 不再弹窗问「起始学年 / 第一学期·第二学期」（上游用 showPrompt + showSingleSelection
    //      拼 xnxq01id）：课表页本来就有「学年学期」下拉框，用户切到哪一学期就导哪一学期
    //      （只读选中项的 value 与文字，读不到就让教务用它自己的默认学期）；
    //   ② 不再弹窗问校区（共青城 / 上海路）——校区只影响作息表，写进 warnings 让用户核对，
    //      比拦在提取流程里问一句更省事，也不依赖提问桥（见 AUDIT.md §7）；
    //   ③ 只交原始结构出去（学期 + 每一格的网格列号 col + 原始 HTML + 表宽 cols），
    //      课程名/教师/教室/周次/节次的解释全在 parse.js —— 那边 CI 里能真跑；
    //   ④ 去掉上游的 showAlert / showToast / notifyTaskCompletion 与那个写进页面的
    //      window.validateYearInput 全局函数（空课的导入流程自己会确认，脚本对页面只读）。
    //
    // 只请求当前页面同源的一台教务服务器：不读账号密码、不写页面、不外发任何数据。
    var KB_PATH = '/jsxsd/xskb/xskb_list.do';
    var LOGIN_HINT = '请先在页面里登录教务系统并打开「课表查询」，确认能看到课表再点提取';

    function clean(value) {
        if (value === null || value === undefined) return '';
        return String(value).replace(/\s+/g, ' ').trim();
    }

    function pad2(n) {
        return (n < 10 ? '0' : '') + n;
    }

    // 提取时刻（本地日期）。只用于教务页面给不出开学日时推算第 1 周 —— 见 parse.js。
    // 不能用 toISOString：北京时间早上 8 点前它会退到前一天。
    function todayIso() {
        var now = new Date();
        return now.getFullYear() + '-' + pad2(now.getMonth() + 1) + '-' + pad2(now.getDate());
    }

    function tagOf(node) {
        return String((node && node.tagName) || '').toUpperCase();
    }

    // 一格里的「明细」：强智把课程名 + 教师/周次(节次)/教室放在 div.kbcontent 里，
    // 同一个格子里放多门课时，几门课在**同一个** div 里用一长串减号隔开（切开是 parse.js 的事）。
    // 同一个格子里也可能有可见简写与 display:none 的完整版两份：有隐藏的就只取隐藏的那份
    //（上游取的是 querySelectorAll('div.kbcontent') 全部，可见简写会被它一起收进来 ——
    //  简写里没有「周次(节次)」，多收的那份在 parse.js 里只会变成一条「没认出节次」的警告）；
    // 没有隐藏的就全取（多个 kbcontent 就是多门课，只取第一份会丢课）。
    function partsOf(cell) {
        var divs = cell.getElementsByTagName('div');
        var hidden = [];
        var any = [];
        for (var i = 0; i < divs.length; i++) {
            if (String(divs[i].className || '').indexOf('kbcontent') < 0) continue;
            any.push(divs[i]);
            var style = divs[i].getAttribute ? String(divs[i].getAttribute('style') || '') : '';
            if (style.indexOf('none') >= 0) hidden.push(divs[i]);
        }
        var picked = hidden.length ? hidden : any;
        var parts = [];
        for (var j = 0; j < picked.length; j++) {
            var html = String(picked[j].innerHTML || '').trim();
            if (html && html !== '&nbsp;') parts.push(html);
        }
        return parts;
    }

    function cellOf(cell, col, span) {
        var text = cell.textContent !== undefined ? cell.textContent : cell.innerText;
        return { col: col, span: span, text: clean(text), parts: partsOf(cell) };
    }

    function intAttr(node, name) {
        var raw = node && node.getAttribute ? node.getAttribute(name) : null;
        var value = parseInt(raw, 10);
        return isNaN(value) || value < 1 ? 1 : value;
    }

    // 把表格还原成二维网格：每个格子带上它在网格里的**列号** col 与跨列数 span，
    // 表宽（列数）也一起交出去。不能让 parse.js 拿「第几个 td」当星期几：
    //   · 强智的课表首列是「节次」列，它常常 rowspan 跨两行 —— 被跨掉的那一行里第一个 td
    //     落在 col 1，按数组下标算会把整行错一天；
    //   · 跨列的 colspan 同理（后面的格子整排右移）。
    // 合并单元格占的其它格子**只占位、不复制内容**：把一门课复制到它没占的列上，
    // 等于把这门课算到别的星期去，那是错的。被跨掉、又没有任何内容的那一行不交出去。
    function gridOf(table) {
        var grid = [];
        var trs = table.rows || table.getElementsByTagName('tr');
        for (var r = 0; r < trs.length; r++) {
            var line = grid[r] || (grid[r] = []);
            var col = 0;
            var kids = trs[r].children || [];
            for (var k = 0; k < kids.length; k++) {
                var node = kids[k];
                var tag = tagOf(node);
                if (tag !== 'TD' && tag !== 'TH') continue;
                while (line[col] !== undefined) col++;
                var rowSpan = intAttr(node, 'rowspan');
                var colSpan = intAttr(node, 'colspan');
                for (var dr = 0; dr < rowSpan; dr++) {
                    var target = grid[r + dr] || (grid[r + dr] = []);
                    for (var dc = 0; dc < colSpan; dc++) target[col + dc] = true;
                }
                line[col] = cellOf(node, col, colSpan);
                col += colSpan;
            }
        }
        var rows = [];
        var width = 0;
        for (var ri = 0; ri < grid.length; ri++) {
            var span = grid[ri] || [];
            var cells = [];
            var hasContent = false;
            for (var ci = 0; ci < span.length; ci++) {
                var cell = span[ci];
                if (cell === undefined || cell === true) continue;
                if (cell.col + cell.span > width) width = cell.col + cell.span;
                if (cell.text || cell.parts.length) hasContent = true;
                cells.push(cell);
            }
            if (!cells.length || !hasContent) continue;
            rows.push(cells);
        }
        return { rows: rows, cols: width };
    }

    // 课表容器：上游写死 #kbtable（强智学生端的通行 id）。同族有部署用 #timetable，
    // 再兜底找「有 .kbcontent 格子的那张表」。
    function findTable(doc) {
        var byId = doc.getElementById('kbtable') || doc.getElementById('timetable');
        if (byId) return byId;
        var cell = doc.querySelector ? doc.querySelector('.kbcontent') : null;
        if (!cell) return null;
        var node = cell;
        while (node && node.nodeType === 1) {
            if (tagOf(node) === 'TABLE') return node;
            node = node.parentNode;
        }
        return null;
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

    var TERM_ID = /^\d{4}-\d{4}-\d$/;

    // 学年学期：强智用 xnxq01id 这个参数（值形如 2026-2027-1）。
    // 三个来源依次找：① 页面上的下拉框选中项 ② 页面里的隐藏域 / URL 查询串 ③ 都没有。
    // 只读这一个字段的 value 与 option 文字 —— 页面上其它内容一律不读
    //（AUDIT.md「读取面」一节列了全部读取点）。
    function termFromSelect(doc) {
        var selects = doc.getElementsByTagName('select');
        for (var i = 0; i < selects.length; i++) {
            var identity = String(selects[i].id || '') + ' ' + String(selects[i].name || '');
            if (identity.indexOf('xnxq') < 0) continue;
            var options = selects[i].options || [];
            var firstCode = null;
            var firstName = null;
            for (var j = 0; j < options.length; j++) {
                var value = clean(options[j].value);
                if (!TERM_ID.test(value)) continue;
                if (firstCode === null) {
                    firstCode = value;
                    firstName = clean(options[j].text);
                }
                if (options[j].selected) return { code: value, name: clean(options[j].text) };
            }
            if (firstCode) return { code: firstCode, name: firstName };
        }
        return null;
    }

    function termFromHidden(doc) {
        var found = null;
        var inputs = doc.getElementsByTagName('input');
        for (var i = 0; i < inputs.length; i++) {
            var identity = String(inputs[i].id || '') + ' ' + String(inputs[i].name || '');
            if (identity.indexOf('xnxq') < 0) continue;
            var value = clean(inputs[i].value);
            if (TERM_ID.test(value)) {
                found = { code: value, name: null };
                break;
            }
        }
        if (found) return found;
        // 上一页跳过来时学期号常常就在地址里（?xnxq01id=2026-2027-1）
        var href = String((window.location && window.location.search) || '');
        var m = /[?&]xnxq01id=([^&]+)/.exec(href);
        if (m) {
            var fromUrl = clean(decodeURIComponent(m[1]));
            if (TERM_ID.test(fromUrl)) return { code: fromUrl, name: null };
        }
        return null;
    }

    function readTerm(doc) {
        return termFromSelect(doc) || termFromHidden(doc) || { code: null, name: null };
    }

    function harvest(doc) {
        var table = findTable(doc);
        var grid = table ? gridOf(table) : { rows: [], cols: 0 };
        return {
            term: readTerm(doc),
            cols: grid.cols,
            rows: grid.rows
        };
    }

    // pageUrl 一并交出去：出问题时这一条最有用（能看出用户是从哪个入口进的教务）。
    // 不含任何个人信息。
    function payloadOf(flight) {
        return JSON.stringify({
            source: 'qz-jsxsd-xskb',
            pageUrl: String(window.location.href),
            now: todayIso(),
            term: flight.term,
            cols: flight.cols,
            rows: flight.rows
        });
    }

    // 上游的表单体，逐字照搬（只把学期号换成从页面读到的那个）。
    function requestHtml(termId) {
        var body = 'jx0404id=&cj0701id=&zc=&demo=&xnxq01id=' + encodeURIComponent(termId || '');
        // 相对路径 + same-origin：请求落回当前页面所在的那台服务器（含它的端口）
        return fetch(KB_PATH, {
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

    function htmlToDoc(html) {
        return new DOMParser().parseFromString(html, 'text/html');
    }

    var live = harvest(document);
    if (courseCells(live.rows) > 0) return payloadOf(live);

    return requestHtml(live.term.code).then(function (html) {
        var got = harvest(htmlToDoc(html));
        if (courseCells(got.rows) === 0) {
            throw new Error(
                '教务返回的课表是空的：可能本学期还没排课，或者登录状态已失效。' + LOGIN_HINT
            );
        }
        // 当前页面在别的页面（不是课表页）时，学期下拉框通常不在，就用课表页自己那份
        return payloadOf({
            term: (got.term && got.term.code) ? got.term : live.term,
            cols: got.cols,
            rows: got.rows
        });
    });
})()
