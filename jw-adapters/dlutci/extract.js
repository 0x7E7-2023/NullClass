(function () {
    // 大连工程学院（原大连理工大学城市学院）教务系统 —— 金智教育 jwapp 平台。
    //
    // 取数方式：同源接口（不用 DOM 抓取，页面改版不影响）。
    //   ① /modules/xskcb/cxxsjbxx.do   当前登录学生（拿学号，无参数即可）
    //   ② /modules/jshkcb/dqxnxq.do    当前学年学期
    //   ③ /modules/xskcb/cxxljc.do     校历：开学日期 XQKSRQ、总周数 ZZC
    //   ④ /modules/xskcb/xskcb.do      课表行（必须带 XH + XNXQDM，分页）
    //
    // 输出里**已剔除学号与姓名**（XH / XM）——只留排课信息，顺带让 fixture 天然脱敏。
    //
    // 注意 manifest 里**不写 scheduleUrlHint**：金智的模块页 URL 形如
    // /jwapp/sys/wdkb/*default/index.do#/xskcb，用它当登录跳转目标时，新会话会直接
    // 落到模块页并拿到 403（实测：从门户登录再进模块页则正常）。取数只走接口，
    // 停在门户首页也能提取，所以让 WebView 从登录页（根地址）起就够了。
    var BASE = '/jwapp/sys/wdkb';

    function post(path, body) {
        return fetch(BASE + path, {
            method: 'POST',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8' },
            body: body || ''
        }).then(function (response) {
            if (response.status === 403) {
                throw new Error('教务系统拒绝访问（403）：请先在页面里登录，并确认能看到「我的课表」');
            }
            if (response.status < 200 || response.status >= 300) {
                throw new Error('教务系统返回 HTTP ' + response.status);
            }
            // 未登录时教务系统会 302 到 http 登录页，WebView 拦掉混合内容 → fetch 直接失败；
            // 兜底把「解析不出 JSON」也翻译成同一句人话。
            return response.json().then(null, function () {
                throw new Error('教务系统没有返回课表数据：登录状态可能已失效，请在页面里重新登录后再点「提取课表」');
            });
        }, function () {
            throw new Error('连不上教务系统：登录状态可能已失效，请在页面里重新登录后再点「提取课表」');
        });
    }

    function rowsOf(json, key) {
        if (!json || String(json.code) !== '0' || !json.datas || !json.datas[key]) {
            throw new Error('教务系统返回了意料之外的数据（缺少 ' + key + '）');
        }
        return json.datas[key].rows || [];
    }

    function fetchScheduleRows(xh, termCode) {
        var pageSize = 200;
        var collected = [];

        function nextPage(page) {
            var body = 'XH=' + encodeURIComponent(xh) +
                '&XNXQDM=' + encodeURIComponent(termCode) +
                '&pageSize=' + pageSize +
                '&pageNumber=' + page;
            return post('/modules/xskcb/xskcb.do', body).then(function (json) {
                if (!json || String(json.code) !== '0' || !json.datas || !json.datas.xskcb) {
                    throw new Error('课表接口返回了意料之外的数据');
                }
                var data = json.datas.xskcb;
                var rows = data.rows || [];
                for (var i = 0; i < rows.length; i++) collected.push(rows[i]);
                var total = parseInt(data.totalSize, 10);
                if (isNaN(total)) total = collected.length;
                // 兜底：最多翻 20 页（200×20 条），防止接口异常时死循环
                if (collected.length < total && rows.length > 0 && page < 20) {
                    return nextPage(page + 1);
                }
                return collected;
            });
        }
        return nextPage(1);
    }

    // 校历里按「学年 + 学期」找到本学期那一行
    function findCalendar(rows, termCode) {
        var parts = String(termCode).split('-');
        if (parts.length < 3) return null;
        var xn = parts[0] + '-' + parts[1];
        var xq = parts[2];
        for (var i = 0; i < rows.length; i++) {
            if (String(rows[i].XN) === xn && String(rows[i].XQ) === xq) return rows[i];
        }
        return null;
    }

    var PERSONAL = { XH: true, XM: true };

    function withoutPersonal(rows) {
        var out = [];
        for (var i = 0; i < rows.length; i++) {
            var src = rows[i];
            var dst = {};
            for (var key in src) {
                if (src.hasOwnProperty(key) && !PERSONAL[key]) dst[key] = src[key];
            }
            out.push(dst);
        }
        return out;
    }

    return post('/modules/xskcb/cxxsjbxx.do', '').then(function (me) {
        var meRows = rowsOf(me, 'cxxsjbxx');
        if (!meRows.length || !meRows[0].XH) {
            throw new Error('没能取到当前登录学生的学号，请重新登录教务系统后再试');
        }
        var xh = meRows[0].XH;

        return Promise.all([
            post('/modules/jshkcb/dqxnxq.do', ''),
            post('/modules/xskcb/cxxljc.do', '')
        ]).then(function (res) {
            var termRows = rowsOf(res[0], 'dqxnxq');
            if (!termRows.length || !termRows[0].DM) {
                throw new Error('没能取到当前学年学期，请稍后重试');
            }
            var term = termRows[0];
            var calendar = findCalendar(rowsOf(res[1], 'cxxljc'), term.DM);

            return fetchScheduleRows(xh, term.DM).then(function (rows) {
                return JSON.stringify({
                    term: {
                        code: term.DM,
                        name: term.MC,
                        firstDay: calendar && calendar.XQKSRQ ? String(calendar.XQKSRQ).substring(0, 10) : null,
                        totalWeeks: calendar && calendar.ZZC ? parseInt(calendar.ZZC, 10) : null
                    },
                    rows: withoutPersonal(rows)
                });
            });
        });
    });
})()
