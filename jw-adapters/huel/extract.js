(function () {
    // 河南财经政法大学教务适配器（正方新版 jwglxt 平台）
    // 移植自 shiguang_warehouse 的 HUEL/huel_01.js
    //   https://github.com/XingHeYuZhuan/shiguang_warehouse  （MIT，上游 maintainer Mercury）
    //   上游快照 e62554a4034386b893bcd6813c7b2b64f8c730a3（2026-09-12）
    // 移植改动：
    //   ① ES6 改成 ES5：async/await 改成 then 链，去掉模板串、箭头函数与块级作用域声明
    //   ② 不写死主机、也不要求停在课表页：上游把 https://xk.huel.edu.cn 写死在脚本里，并要求
    //      当前页正好是课表页；这里用 window.location.origin 拼路径，用户在**教务主机上**的任意一页
    //      （登录首页、教务站内的任一页）都能提取 —— 学年学期读不到时会自己取一次课表页 HTML 来读，
    //      所以不必先跳到课表页。
    //      **注意边界**：本适配器 allowHosts 为空，白名单只有 loginUrl 主机 xk.huel.edu.cn；
    //      若用户停在别的主机（CAS 认证域、门户、WebVPN 网关子域）上点提取，同源请求会被沙箱
    //      拦掉（报「禁止访问白名单之外的地址」）。要支持那种入口就得把网关域写进 allowHosts，
    //      而目前没有证据表明本校的教务在网关后面，所以不预先放行。
    //   ③ 上游弹窗问「学年」「学期」「开学日期」；这里自动取教务当前选中的学年学期，开学日期
    //      由 parse.js 按学期推算并写进载荷 warnings（本适配器不依赖提问桥）
    //   ④ 只取数：把接口返回的排课行（kbList）原样交出去，周次解析、课程合并、节次表都在 parse.js
    //   ⑤ 上游不看记录总数：这里读响应信封里的总数字段（各部署字段名不同），发现总数大于实到
    //      行数就补一次带 queryModel 参数的请求；补不全就把总数交给 parse.js 写进 warnings，
    //      绝不静默地只取第一页
    //   ⑥ 响应里夹带的学生信息（xsxx 等）不带走：只交出 kbList 排课行
    //
    // 取数方式：同源接口，最多三个请求，全部打在本校教务主机上：
    //   ① GET  /jwglxt/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=N2151&layout=default  读当前学年学期
    //      （当前页面上已经有 #xnm / #xqm 时跳过这一步）
    //   ② POST /jwglxt/kbcx/xskbcx_cxXsgrkb.html?gnmkdm=N2151                     取课表（JSON）
    //   ③ 同 ② 的地址（只在「信封报的总数大于实到行数」时补一次）
    // 地址都由 window.location.origin 拼出来，不出本校域，所以 manifest 的 allowHosts 是空的。
    // 登录全程由用户在 WebView 里手工完成，本脚本不读、不存、不上报任何账号信息。

    var JWLGXT = '/jwglxt';
    // 正方「学生课表查询」的菜单号；上游 HUEL 脚本用的就是这个（同平台 80 个上游脚本里也是 N2151 最多）
    var GNMKDM = 'N2151';
    // 课表响应超过这个长度就不认（多半是登录页 HTML 或改版页面）
    var MAX_BODY = 2000000;
    // 各部署在信封里报记录总数用的字段名（正方不同模块不一样，逐一看一眼；读不到就算没有）
    var TOTAL_FIELDS = ['totalResult', 'total', 'totalCount', 'totalRow', 'records'];

    function originOf() {
        var loc = window.location;
        if (loc.origin) return loc.origin;
        return loc.protocol + '//' + loc.host;
    }

    // 正方新版的上下文路径是 /jwglxt；万一挂在前缀下（门户 / 网关），跟着当前地址里的那一段走
    function jwBase() {
        var path = String(window.location.pathname || '');
        var at = path.indexOf(JWLGXT + '/');
        if (at > 0) return originOf() + path.substring(0, at + JWLGXT.length);
        return originOf() + JWLGXT;
    }

    function request(url, method, body) {
        var options = {
            method: method,
            credentials: 'include',
            headers: {
                'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8',
                'X-Requested-With': 'XMLHttpRequest',
                'Accept': '*/*'
            }
        };
        if (typeof body === 'string') options.body = body;
        return fetch(url, options).then(function (response) {
            if (response.status === 403) {
                throw new Error(
                    '教务系统拒绝访问（403）：请先在页面里登录，确认能看到课表后再点「提取课表」'
                );
            }
            if (response.status < 200 || response.status >= 300) {
                throw new Error(
                    '教务系统返回 HTTP ' + response.status +
                    '：登录状态可能已失效，请重新登录后再点「提取课表」'
                );
            }
            return response.text();
        }, function (error) {
            var detail = error && error.message ? '（' + error.message + '）' : '';
            throw new Error('连不上教务系统' + detail + '：请确认已登录，再点「提取课表」');
        });
    }

    function get(url) {
        return request(url, 'GET', null);
    }

    function post(url, body) {
        return request(url, 'POST', body);
    }

    // 读一个学年学期字段：正常是下拉框（跳过 value 为空的占位项，被 selected 标记的那项优先，
    // 没标记就取第一项）；万一页面把它放在隐藏 input 里，就直接读 value（上游也是读 value 的）
    function readSelect(selectEl) {
        if (!selectEl) return null;
        var options = selectEl.options;
        if (!options && selectEl.querySelectorAll) options = selectEl.querySelectorAll('option');
        if (!options || !options.length) {
            var raw = String(selectEl.value === null || selectEl.value === undefined ? '' : selectEl.value)
                .replace(/\s+/g, '');
            return raw ? { value: raw, text: '' } : null;
        }
        var list = [];
        var picked = -1;
        var i;
        for (i = 0; i < options.length; i++) {
            var option = options[i];
            var value = String(option.value === null || option.value === undefined ? '' : option.value)
                .replace(/\s+/g, '');
            if (!value) continue;
            var label = String(option.textContent === null || option.textContent === undefined ? '' : option.textContent)
                .replace(/\s+/g, ' ').trim();
            if (option.selected && picked < 0) picked = list.length;
            list.push({ value: value, text: label });
        }
        if (!list.length) return null;
        if (picked < 0) picked = 0;
        return { value: list[picked].value, text: list[picked].text };
    }

    function readTermFromDoc(doc) {
        if (!doc || !doc.querySelector) return null;
        var year = readSelect(doc.querySelector('#xnm'));
        var season = readSelect(doc.querySelector('#xqm'));
        if (!year || !season) return null;
        return { xnm: year.value, xqm: season.value, xnmText: year.text, xqmText: season.text };
    }

    var TERM_NOT_FOUND = '没读到学年学期：登录状态可能已失效，或教务系统改了课表页。' +
        '请重新登录并打开「信息查询 - 学生课表查询」后再点「提取课表」';

    function currentTerm() {
        var onPage = readTermFromDoc(window.document);
        if (onPage) {
            onPage.source = 'page';
            return Promise.resolve(onPage);
        }
        if (typeof DOMParser === 'undefined') {
            throw new Error(
                '这个页面里读不到学年学期：请先在教务系统里打开「信息查询 - 学生课表查询」，再点「提取课表」'
            );
        }
        return get(jwBase() + '/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=' + GNMKDM + '&layout=default')
            .then(function (html) {
                var doc = new DOMParser().parseFromString(String(html), 'text/html');
                var term = readTermFromDoc(doc);
                if (!term) throw new Error(TERM_NOT_FOUND);
                term.source = 'index';
                return term;
            });
    }

    function numberOrNull(value) {
        if (value === null || value === undefined) return null;
        var n = parseInt(value, 10);
        return isNaN(n) ? null : n;
    }

    // 信封里报的记录总数（没有这个字段就是 null —— 「没报总数」和「报了 0 条」是两回事）
    function declaredTotalOf(json) {
        var i;
        for (i = 0; i < TOTAL_FIELDS.length; i++) {
            var n = numberOrNull(json[TOTAL_FIELDS[i]]);
            if (n !== null && n >= 0) return n;
        }
        return null;
    }

    // 排课行数组：正方课表接口是 kbList；万一某个部署把行放在 rows（可能是 JSON 字符串）里也认
    function rowsOf(json) {
        if (json.kbList instanceof Array) return json.kbList;
        if (json.rows instanceof Array) return json.rows;
        if (typeof json.rows === 'string' && json.rows) {
            try {
                var parsed = JSON.parse(json.rows);
                if (parsed instanceof Array) return parsed;
            } catch (e) {
                return null;
            }
        }
        return null;
    }

    function parseBody(text) {
        if (String(text).length > MAX_BODY) return null;
        var json = null;
        try {
            json = JSON.parse(String(text));
        } catch (e) {
            return null;
        }
        if (!json || typeof json !== 'object') return null;
        return json;
    }

    function badResponse(message) {
        throw new Error(message);
    }

    // 取课表行。若信封报了总数且大于实到行数，就补一次带 queryModel 参数的请求
    // （正方分页查询的公共参数）；补完仍不足则如实把总数交出去，由 parse.js 写进 warnings
    function fetchRows(term) {
        var url = jwBase() + '/kbcx/xskbcx_cxXsgrkb.html?gnmkdm=' + GNMKDM;
        var body = 'xnm=' + encodeURIComponent(term.xnm) +
            '&xqm=' + encodeURIComponent(term.xqm) +
            '&kzlx=ck&xsdm=&kclbdm=&kclxdm=';
        var meta = { declaredTotal: null, followUp: 'not-needed' };

        function result(rows) {
            return { rows: rows, meta: meta };
        }

        return post(url, body).then(function (text) {
            var json = parseBody(text);
            if (!json) {
                badResponse('教务系统没有返回课表数据（返回的不是课表 JSON）：' +
                    '登录状态可能已失效，请重新登录后再点「提取课表」');
            }
            var rows = rowsOf(json);
            if (!rows) {
                badResponse('教务系统返回的课表 JSON 里没有排课行：' +
                    '教务系统可能已改版，请把这条提示反馈给适配器维护者');
            }
            var total = declaredTotalOf(json);
            if (total === null || total <= rows.length) return result(rows);

            // 报了总数却没给够行数：按总数再要一次（一次为限，不循环翻页）
            meta.declaredTotal = total;
            var pagedBody = body + '&queryModel.showCount=' + total + '&queryModel.currentPage=1';
            return post(url, pagedBody).then(function (text2) {
                var more = parseBody(text2);
                var rows2 = more ? rowsOf(more) : null;
                if (rows2 && rows2.length > rows.length) {
                    meta.followUp = rows2.length >= total ? 'ok' : 'short';
                    return result(rows2);
                }
                meta.followUp = 'failed';
                return result(rows);
            }, function () {
                meta.followUp = 'failed';
                return result(rows);
            });
        });
    }

    return currentTerm().then(function (term) {
        return fetchRows(term).then(function (got) {
            return JSON.stringify({
                term: {
                    xnm: term.xnm,
                    xqm: term.xqm,
                    xnmText: term.xnmText,
                    xqmText: term.xqmText
                },
                raw: { kbList: got.rows },
                meta: {
                    termSource: term.source,
                    declaredTotal: got.meta.declaredTotal,
                    followUp: got.meta.followUp
                }
            });
        });
    });
})()
