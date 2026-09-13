(function () {
    // 长春工程学院 教务适配器 —— 强智科技「高校综合管理教务系统」（学生端 /jsxsd/），走 WebVPN。
    // 移植自 shiguang_warehouse 的 CCIT/ccit.js
    //   https://github.com/XingHeYuZhuan/shiguang_warehouse  （MIT，上游作者 星河欲转）
    //   上游快照 commit e62554a4034386b893bcd6813c7b2b64f8c730a3（2026-09-12）
    // 同目录 adapters.yaml：adapter_name「长春工程学院强智教务」，maintainer「星河欲转」，
    //   import_url = https://webvpn.ccit.edu.cn/auth/login
    //
    // 平台判据（按脚本**实际请求的接口路径**，不是按上游注释）：
    //   上游只请求一条路径 —— POST /jsxsd/xskb/xskb_list.do，body 里带 xnxq01id=<学年学期>，
    //   返回服务端渲染的 HTML 课表（#kbtable 里的 div.kbcontent）。这是强智学生端的课表接口。
    //
    // 关于请求的域（这所学校的关键点，见 AUDIT.md §1）：
    //   上游把 WebVPN 映射后的主机名写死成
    //     https://http-10-198-47-148-8080.webvpn.ccit.edu.cn/jsxsd/xskb/xskb_list.do
    //   （网关把内网 http://10.198.47.148:8080 编码进主机名）。
    //   那是**当前会话**的映射结果，换一个网关或换一台内网服务器就不是这个名字了。
    //   所以这里**不硬编码任何主机名**，只请求**当前页面同源**的相对路径：
    //   用户从哪个入口进来的（WebVPN 映射域 / 校内直连），请求就落回哪台服务器。
    //   manifest 只放行 *.webvpn.ccit.edu.cn（网关下的所有映射域）。
    //
    // 移植改动：
    //   ① 不再弹窗问「起始学年 / 第一学期·第二学期」（上游拼出 xnxq01id 再 POST）：
    //      课表页顶部本来就有「学年学期」下拉框，用户切到哪一学期就导哪一学期，
    //      从下拉框的选中项读；读不到就不带这个参数，让教务用它自己的默认学期；
    //   ② 当前页面已经画着课表（#kbtable 里有课）时**直接用它**，一个请求都不发 ——
    //      走 WebVPN 时这一条最稳（用户看见什么就导什么）；
    //   ③ 只交原始结构出去（学期 + 每一格的网格列号 col/span + 原始 HTML + 表宽 cols），
    //      课程名/教师/教室/周次/节次的解释全在 parse.js —— 那边 CI 里能真跑；
    //   ④ 去掉上游的 showAlert / showToast / notifyTaskCompletion，以及写进页面的
    //      window.validateYearInput 全局函数（空课的导入流程自己会确认，脚本对页面只读）；
    //   ⑤ 星期交给网格列号（强智课表首列是「节次」列、常常 rowspan 跨行），
    //      上游按「第几个 td 就是星期几」算会把整行错一天 —— 修正落在 parse.js，
    //      extract.js 的职责是**如实交出列号**。
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

    function classTokens(node) {
        var cls = (node && node.className !== undefined) ? String(node.className) : '';
        return ' ' + cls.replace(/\s+/g, ' ').trim() + ' ';
    }

    function isHidden(node) {
        var style = (node && node.getAttribute) ? String(node.getAttribute('style') || '') : '';
        return style.indexOf('none') >= 0;
    }

    // 一格里的「明细」：强智把课程名 + 教师/周次(节次)/教室放在 div.kbcontent 里。
    // 上游取的是 div.kbcontent（不是可见的那份简写）—— 这里先按**类名整体等于 kbcontent**
    // 选（' kbcontent ' 这样比对，免得把 kbcontent1 / kbcontent2 也当成一份），
    // 一份都没有时再退回「类名里含 kbcontent」的 div。同一个格子里有可见简写与
    // display:none 的完整版两份时优先取隐藏的那份；只取一份，避免同一门课被算两次。
    function partsOf(cell) {
        var divs = cell.getElementsByTagName('div');
        var exact = [];
        var loose = [];
        for (var i = 0; i < divs.length; i++) {
            var tokens = classTokens(divs[i]);
            if (tokens.indexOf(' kbcontent ') >= 0) exact.push(divs[i]);
            else if (tokens.indexOf('kbcontent') >= 0) loose.push(divs[i]);
        }
        var pool = exact.length ? exact : loose;
        var hidden = [];
        for (var j = 0; j < pool.length; j++) {
            if (isHidden(pool[j])) hidden.push(pool[j]);
        }
        var picked = hidden.length ? hidden : (pool.length ? [pool[0]] : []);
        var parts = [];
        for (var k = 0; k < picked.length; k++) {
            var html = String(picked[k].innerHTML || '').trim();
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
                if (cell.col + 1 > width) width = cell.col + 1;
                if (cell.text || cell.parts.length) hasContent = true;
                cells.push(cell);
            }
            if (!cells.length || !hasContent) continue;
            rows.push(cells);
        }
        return { rows: rows, cols: width };
    }

    // 课表容器：上游写死 #kbtable（强智学生端的通行 id）。兜底找「有 .kbcontent 格子的那张表」。
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

    // 学年学期：课表页顶部的下拉框（强智是 select[id|name 含 xnxq]，值形如 2026-2027-1）。
    // 只读**选中项**的 value 与 option 文字，用户切到哪一学期就跟着他走。
    // 页面上其它内容一律不读（AUDIT.md「读取面」一节列了全部读取点）。
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

    function harvest(doc) {
        var table = findTable(doc);
        var grid = table ? gridOf(table) : { rows: [], cols: 0 };
        return {
            term: readTerm(doc),
            cols: grid.cols,
            rows: grid.rows
        };
    }

    // pageUrl 一并交出去：WebVPN 网关会把内网地址重写进主机名，出问题时这一条最有用
    //（真机抽验时能一眼看出用户是从网关进的还是校内直连的）。不含任何个人信息。
    function payloadOf(term, cols, rows) {
        return JSON.stringify({
            source: 'qz-jsxsd-xskb',
            pageUrl: String(window.location.href),
            now: todayIso(),
            term: term || { code: null, name: null },
            cols: cols,
            rows: rows
        });
    }

    // 上游的请求体逐字照搬，只把 xnxq01id 换成「当前页选中的那一学期」。
    // 拿不到学期号时不带这个参数（也不发 POST 体），让教务用它自己的默认学期。
    function requestHtml(termCode) {
        var init = { method: 'GET', credentials: 'same-origin' };
        if (termCode) {
            init = {
                method: 'POST',
                credentials: 'same-origin',
                headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                body: 'jx0404id=&cj0701id=&zc=&demo=&xnxq01id=' + encodeURIComponent(termCode)
            };
        }
        // 相对路径 + same-origin：请求落回当前页面所在的那台服务器（校外即 WebVPN 映射域）
        return fetch(KB_PATH, init).then(function (response) {
            if (response.status === 401 || response.status === 403) {
                throw new Error('教务系统拒绝访问（' + response.status + '）：' + LOGIN_HINT);
            }
            if (response.status < 200 || response.status >= 300) {
                throw new Error('教务系统返回 HTTP ' + response.status + '：' + LOGIN_HINT);
            }
            return response.text();
        }, function () {
            throw new Error('连不上教务系统：登录状态可能已失效，或当前不在教务页面。' + LOGIN_HINT);
        });
    }

    function htmlToDoc(html) {
        return new DOMParser().parseFromString(html, 'text/html');
    }

    var live = harvest(document);
    if (courseCells(live.rows) > 0) return payloadOf(live.term, live.cols, live.rows);

    var liveCode = (live.term && live.term.code) ? live.term.code : null;
    return requestHtml(liveCode).then(function (html) {
        var got = harvest(htmlToDoc(html));
        if (courseCells(got.rows) === 0) {
            throw new Error(
                '教务返回的课表是空的：可能本学期还没排课，或者当前页面不是教务课表页' +
                '（比如停在 WebVPN 门户首页）。' + LOGIN_HINT
            );
        }
        // 当前页面不是课表页时，学期下拉框通常不在 —— 那就用取回来的那份
        var term = (got.term && got.term.code) ? got.term : live.term;
        return payloadOf(term, got.cols, got.rows);
    });
})()
