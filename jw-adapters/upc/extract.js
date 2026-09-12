(function () {
    // 中国石油大学（华东）本科教务适配器（湖南强智 · 学生端 /jsxsd/）—— 第一步：取数。
    //
    // 移植自 shiguang_warehouse 的 UPC/upc.js
    //   https://github.com/XingHeYuZhuan/shiguang_warehouse  （MIT，作者 星河欲转）
    //
    // 上游取数方式：向 WebVPN 重写域 POST 一张服务端渲染的课表页（强智给的是 HTML，不是 JSON）：
    //   https://jwxt-443.webvpn.upc.edu.cn/jsxsd/xskb/xskb_list.do
    //   body: cj0701id=&zc=&demo=&xnxq01id=<学年学期>
    // 上游 adapters.yaml 的 import_url 是 https://webvpn.upc.edu.cn/users/sign_in
    // —— 校外的唯一入口是 WebVPN：先登录网关，再从网关里打开教务系统。
    //
    // 移植改动：
    //   ① 不写死主机名：一律请求**当前页面同源**的 /jsxsd/xskb/xskb_list.do。用户从 WebVPN 打开
    //      教务后，页面本身就在重写子域（jwxt-443.webvpn.upc.edu.cn 这类）上；校内直连
    //      （jwxt.upc.edu.cn，教务处通知里的教务地址）走同一条相对路径。上游那个绝对 URL
    //      只出现在注释与错误提示里，脚本不会向它发请求（手册 §5：WebVPN 学校请求当前页面同源）。
    //   ② 不再弹窗问「起始学年 / 第几学期」：学年学期从课表页的「学年学期」下拉框里取
    //      （读不到就按教务默认取当前学期）。
    //   ③ 当前页面已经有课表就不再发请求（用户此刻看到的正是要导入的那张）。
    //   ④ 每个格子只取一份明细：优先取「带 教师/周次(节次) 字样的那份 div」，同分时取隐藏的那份
    //      —— 上游把 div.kbcontent 与 div.kbcontent1 两份都算了，同一门课会被算两次。
    //   ⑤ 只交原始结构出去（提取时刻 + 学年学期 + 每一格的原始 HTML），周次/节次/课名的解释
    //      全在 parse.js —— 那边 CI 里能用 Rhino 真跑。
    //
    // 请求只发往当前页面同源主机（WebVPN 网关的重写子域，或教务自身）：不读账号密码、
    // 不写页面、不外发任何数据。审计见同目录 AUDIT.md。
    var KB_PATH = '/jsxsd/xskb/xskb_list.do';
    var LOGIN_HINT = '请先在 WebVPN（https://webvpn.upc.edu.cn）登录，再从网关打开「教务系统」，' +
        '停在课表查询页（/jsxsd/xskb/xskb_list.do）后点「提取课表」';

    function clean(value) {
        if (value === null || value === undefined) return '';
        return String(value).replace(/\s+/g, ' ').trim();
    }

    function pad2(n) {
        return (n < 10 ? '0' : '') + n;
    }

    // 提取时刻（本地日期，不能用 UTC：北京时间早上 8 点前 toISOString 会退到前一天）。
    // 只用于教务给不出开学日时推算第 1 周 —— 见 parse.js。
    function todayIso() {
        var now = new Date();
        return now.getFullYear() + '-' + pad2(now.getMonth() + 1) + '-' + pad2(now.getDate());
    }

    function tagOf(node) {
        return String((node && node.tagName) || '').toUpperCase();
    }

    // 一格里的课程明细。强智有两种 div（class 含 kbcontent / kbcontent1）：一份是完整明细
    // （带 教师 / 周次(节次) / 教室 三个 font），一份是页面上的简写。有的部署把完整那份隐藏、
    // 有的反过来，所以按内容打分只取一份：
    //   带「周次/节次」字样的 +2、带「教师」的 +1、带「教室」的 +1；同分时取隐藏的那份，再同分取第一份。
    // 上游对这一格是 querySelectorAll('.kbcontent, .kbcontent1') 两份都算 —— 同一门课会重复一次。
    function partsOf(cell) {
        var divs = cell.getElementsByTagName('div');
        var best = null;
        var bestScore = -1;
        var bestHidden = false;
        for (var i = 0; i < divs.length; i++) {
            var div = divs[i];
            if (String(div.className || '').indexOf('kbcontent') < 0) continue;
            var html = String(div.innerHTML || '').trim();
            if (!html || html === '&nbsp;') continue;
            var score = 0;
            if (/title\s*=\s*["']?[^"'>]*(周次|节次)/.test(html)) score += 2;
            if (/title\s*=\s*["']?[^"'>]*教师/.test(html)) score += 1;
            if (/title\s*=\s*["']?[^"'>]*教室/.test(html)) score += 1;
            var style = div.getAttribute ? String(div.getAttribute('style') || '') : '';
            var hidden = style.indexOf('none') >= 0;
            if (score > bestScore || (score === bestScore && hidden && !bestHidden)) {
                bestScore = score;
                bestHidden = hidden;
                best = html;
            }
        }
        return best ? [best] : [];
    }

    function cellOf(cell) {
        var text = cell.textContent !== undefined ? cell.textContent : cell.innerText;
        return { text: clean(text), parts: partsOf(cell) };
    }

    function cellsOfRow(row) {
        var out = [];
        var kids = row.children || [];
        for (var i = 0; i < kids.length; i++) {
            var tag = tagOf(kids[i]);
            if (tag === 'TD' || tag === 'TH') out.push(cellOf(kids[i]));
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

    // 课表容器的 id 各校不一（上游用 #timetable，别的强智部署见过 #kbtable），
    // 都没有就按「有 kbcontent 格子的那张表」找。
    function findTable(doc) {
        var byId = doc.getElementById('timetable') || doc.getElementById('kbtable');
        if (byId) return byId;
        var cell = doc.querySelector ? doc.querySelector('.kbcontent') : null;
        return cell ? closestTable(cell) : null;
    }

    function rowsOfTable(table) {
        var out = [];
        var trs = table.rows || table.getElementsByTagName('tr');
        for (var i = 0; i < trs.length; i++) {
            var cells = cellsOfRow(trs[i]);
            for (var c = 0; c < cells.length; c++) {
                if (cells[c].text || cells[c].parts.length) {
                    out.push(cells);
                    break;
                }
            }
        }
        return out;
    }

    function cnNumber(value) {
        if (value === '一') return '1';
        if (value === '二') return '2';
        if (value === '三') return '3';
        return String(value);
    }

    // 兜底用的「学期名候选文字」：只从与学期有关的结构里取，不读整页 ——
    //   ① 页面标题（document.title）：强智写成「学生课表查询」这类页面名，不是个人信息；
    //   ② 页面上每个下拉框的 option 文本：选项是学期名这类固定词，不是自由文本；
    //   ③ id 或 class 里带 xnxq 的元素文字（强智对「学年学期」的命名），且只在文本很短时取
    //      —— 万一命中的是个包着整页的容器，超长的直接不当候选。
    // 取到的整串文字只在本页内存里做一次正则匹配（见 readTerm），不保留、不进载荷、不外发。
    // （与同平台的 hynu 是同一套收窄口径：不读 document.body。）
    var HINT_MAX_LENGTH = 120;

    function termTextHints(doc) {
        var hints = [];
        var title = clean(doc.title);
        if (title) hints.push(title);
        var selects = doc.getElementsByTagName('select');
        for (var i = 0; i < selects.length; i++) {
            var options = selects[i].options || [];
            for (var j = 0; j < options.length; j++) {
                var optionText = clean(options[j].text);
                if (optionText) hints.push(optionText);
            }
        }
        var labels = doc.querySelectorAll
            ? doc.querySelectorAll('[id*="xnxq"], [class*="xnxq"]')
            : [];
        for (var k = 0; k < labels.length; k++) {
            var labelText = clean(labels[k].textContent);
            if (labelText && labelText.length <= HINT_MAX_LENGTH) hints.push(labelText);
        }
        return hints;
    }

    // 学年学期：课表页顶部的下拉框（强智是 select[id|name 含 xnxq]，值形如 2026-2027-1），
    // 取「选中」的那一项 —— 用户在页面上切过学期，这里就跟着他走（不再弹窗问）。
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
        if (!code) {
            // 下拉框认不出：在上面那几处「与学期有关」的文字里认「2026-2027学年第一学期」
            //（整串文字只做这一次匹配，只留下匹配到的那一小段当学期名）
            var hints = termTextHints(doc);
            for (var h = 0; h < hints.length && !code; h++) {
                var m = /(20\d{2})\s*-\s*(20\d{2})\s*学年\s*第?\s*([一二三123])\s*学期/.exec(hints[h]);
                if (!m) continue;
                code = m[1] + '-' + m[2] + '-' + cnNumber(m[3]);
                name = clean(m[0]);
            }
        }
        return { code: code, name: name };
    }

    function harvest(doc) {
        var table = findTable(doc);
        return {
            term: readTerm(doc),
            rows: table ? rowsOfTable(table) : []
        };
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

    function payloadOf(flight) {
        return JSON.stringify({
            now: todayIso(),
            term: flight.term,
            rows: flight.rows
        });
    }

    function htmlToDoc(html) {
        return new DOMParser().parseFromString(html, 'text/html');
    }

    // 上游的请求体，一字未改（cj0701id 是班级、zc 是周次，两个都留空 = 全部；
    // xnxq01id 留空时强智按当前学期返回）。
    function requestHtml(url, body) {
        return fetch(url, {
            method: 'POST',
            credentials: 'same-origin',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
            body: body
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

    function sameOriginUrl() {
        // 少数网关（深信服那套）把内网站点挂在 /http/<加密串>/ 路径前缀下，
        // 绝对化的相对路径会把这个前缀丢掉（请求落到网关根上，不是教务）。
        // 网瑞达式的子域重写没有前缀，这里不会命中；结果永远是**同源**相对路径。
        var match = /^\/(https?)\/([A-Za-z0-9_.-]+)\//.exec(window.location.pathname);
        return (match ? '/' + match[1] + '/' + match[2] : '') + KB_PATH;
    }

    function bodyOf(code) {
        return 'cj0701id=&zc=&demo=&xnxq01id=' + encodeURIComponent(code || '');
    }

    // ① 当前页面已经是课表页：一个请求都不发
    var live = harvest(document);
    if (courseCells(live.rows) > 0) return payloadOf(live);

    // ② 用页面下拉框里选中的学年学期（读不到就留空，按教务默认的当前学期）请求同源课表页
    var asked = live.term.code || '';
    return requestHtml(sameOriginUrl(), bodyOf(asked)).then(function (html) {
        var got = harvest(htmlToDoc(html));
        if (courseCells(got.rows) > 0) {
            return payloadOf({
                term: (got.term && got.term.code) ? got.term : live.term,
                rows: got.rows
            });
        }
        if (!got.term.code || got.term.code === asked) {
            throw new Error('教务返回的课表是空的：可能本学期还没排课，或者登录状态已失效。' + LOGIN_HINT);
        }
        // ③ 第一次没带学期（或带错了）：用返回页下拉框里的学年学期再请求一次
        return requestHtml(sameOriginUrl(), bodyOf(got.term.code)).then(function (again) {
            var flight = harvest(htmlToDoc(again));
            if (courseCells(flight.rows) === 0) {
                throw new Error('教务返回的课表是空的（' + got.term.code + '）：可能本学期还没排课，' +
                    '或者登录状态已失效。' + LOGIN_HINT);
            }
            return payloadOf({
                term: (flight.term && flight.term.code) ? flight.term : got.term,
                rows: flight.rows
            });
        });
    });
})()
