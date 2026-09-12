(function () {
    // 山西工程职业学院 · 超星（chaoxing）教务适配器 —— 取数段（第一步）。
    //
    // 移植自 shiguang_warehouse 的 SXGCXY/sxgcxy_01.js
    //   https://github.com/XingHeYuZhuan/shiguang_warehouse  （MIT，作者 星河欲转）
    // 移植改动：
    //   ① 解析全部搬到 parse.js —— 这里只把教务的原始数据原样交出去；
    //   ② 学年学期不再让用户在「3 年 × 2 学期」里挑：按当前日期推算，取不到就退一个学期，
    //      两个都没有课表数据才报错（上游把当前学期当默认值，这里把它当唯一答案）；
    //   ③ 校区作息时间改成问一句「你在哪个校区」（上游也是弹窗选，但要用户在两个作息表里挑），
    //      只带一个代号走，作息表本身在 parse.js 里；
    //   ④ 学生标识 xhid 只用于本次请求，**不写进输出**（fixture 因此天然脱敏）。
    //
    // 取数方式：**同源接口**，只发一个 GET（页面 DOM 一个都不抓）：
    //   /admin/pkgl/xskb/sdpkkbList?xnxq=<学年学期>&xhid=<页面上的学生标识>
    //
    // 请求域：**只有当前页面这一台主机**。山西工程职业学院的教务是超星托管的
    // （sxevc.jw.chaoxing.com 是超星给这所学校分配的租户子域，与 manifest.loginUrl 同源），
    // 加上下面用的是相对路径，适配器在结构上就不可能请求到别的域 —— 所以 allowHosts 是空的。
    // 特意**不**声明 *.jw.chaoxing.com 或 *.chaoxing.com：那是超星所有租户共用的域，
    // 写进去等于把别的学校的课表接口一起放行，白名单就形同虚设了（见 AUDIT.md）。
    var LIST_PATH = '/admin/pkgl/xskb/sdpkkbList';

    // 校区代号：作息时间表在 parse.js 里，这里只负责问用户是哪一个。
    var CAMPUS_CODES = ['tanghuai', 'longtan'];
    var CAMPUS_LABELS = ['唐槐校区', '龙潭校区'];

    function text(value) {
        if (value === null || value === undefined) return '';
        return String(value).replace(/\s+/g, ' ').trim();
    }

    function termCode(startYear, semester) {
        return startYear + '-' + (startYear + 1) + '-' + semester;
    }

    // 当前学年学期（按日期推算）：9 月–次年 1 月是本学年第一学期，2 月–8 月是上一学年第二学期。
    function currentTerm(now) {
        var year = now.getFullYear();
        var month = now.getMonth() + 1;
        if (month >= 9) return { code: termCode(year, 1), startYear: year, semester: 1 };
        if (month === 1) return { code: termCode(year - 1, 1), startYear: year - 1, semester: 1 };
        return { code: termCode(year - 1, 2), startYear: year - 1, semester: 2 };
    }

    // 上一个学期：用来做兜底（新学期还没排课时，教务对当前学期会返回空）。
    function previousTerm(term) {
        if (term.semester === 1) {
            return { code: termCode(term.startYear - 1, 2), startYear: term.startYear - 1, semester: 2 };
        }
        return { code: termCode(term.startYear, 1), startYear: term.startYear, semester: 1 };
    }

    // 学生标识（超星 jw 页面上的隐藏域）。上游读 #encodeId；这里按可靠性依次多兜几种取法，
    // 一个都取不到就**报错**让用户先打开课表页 —— 不猜、也不填 'UNKNOWN' 那种占位符。
    function studentId() {
        var element = document.getElementById('encodeId');
        if (element && text(element.value)) return text(element.value);

        var named = document.querySelector('input[name="encodeId"]');
        if (named && text(named.value)) return text(named.value);

        var global = window.encodeId;
        if (typeof global === 'string' || typeof global === 'number') {
            if (text(global)) return text(global);
        }

        var fromUrl = /[?&]xhid=([^&#]+)/.exec(window.location.href);
        if (fromUrl) return text(decodeURIComponent(fromUrl[1]));

        return '';
    }

    // 问「你在哪个校区」。两个校区的上下课时间不一样，猜错了整学期的上课时间都是错的，
    // 所以这里宁可问一句 —— 用户取消或者这台设备没有提问桥就返回 null，
    // parse.js 据此**不写作息时间**并写一条核对提示（不猜）。
    function askCampus() {
        // 不用 __ncCapabilities 判断：只要脚本里出现这个名字，宿主就会把本适配器当成
        // 「要用 OCR」的，每次提取前都先等一遍 OCR 引擎探测（首次要几秒）。
        // 直接查提问全局在不在，效果一样，也不用白等。
        if (typeof window.__ncSelect !== 'function') return Promise.resolve(null);

        return window.__ncSelect({
            title: '选择你所在的校区',
            message: '两个校区的作息时间不同（唐槐上午 08:20 上课，龙潭 08:00）。不清楚就取消，' +
                '空课会改用默认节次时间，导入后再到学期管理里核对。',
            items: CAMPUS_LABELS,
            defaultIndex: 0
        }).then(function (index) {
            var picked = parseInt(index, 10);
            if (index === null || index === undefined || isNaN(picked)) return null;
            if (picked < 0 || picked >= CAMPUS_CODES.length) return null;
            return CAMPUS_CODES[picked];
        }, function () {
            // 提问桥不可用 / 参数不合法：按「没选校区」处理，取课表这件事不受影响
            return null;
        });
    }

    // 接口返回的课表行：**结构不对就返回 null**（由调用方翻译成人话），
    // 而不是把半截数据交给 parse.js 去猜。
    function rowsOf(json) {
        if (!json || typeof json !== 'object') return null;
        if (json.ret !== undefined && json.ret !== null && String(json.ret) !== '0') return null;
        var data = json.data;
        if (Array.isArray(data)) return data;
        // 超星的接口有时把列表套一层（{data:{list:[…]}}），顺手认掉
        if (data && typeof data === 'object') {
            var keys = ['list', 'rows', 'records'];
            for (var i = 0; i < keys.length; i++) {
                if (Array.isArray(data[keys[i]])) return data[keys[i]];
            }
        }
        return null;
    }

    function fetchTerm(code, xhid) {
        var url = LIST_PATH + '?xnxq=' + encodeURIComponent(code) + '&xhid=' + encodeURIComponent(xhid);
        var options = {
            method: 'GET',
            credentials: 'include',
            headers: {
                'Accept': 'application/json, text/plain, */*',
                'X-Requested-With': 'XMLHttpRequest'
            }
        };
        return fetch(url, options).then(function (response) {
            if (response.status === 401 || response.status === 403) {
                throw new Error('教务系统拒绝访问（HTTP ' + response.status +
                    '）：请先在页面里登录，再点「提取课表」');
            }
            if (response.status < 200 || response.status >= 300) {
                throw new Error('教务系统返回 HTTP ' + response.status);
            }
            // 登录失效时教务多半 302 到一个非 JSON 页面，把「解析不出 JSON」翻成人话
            return response.json().then(null, function () {
                throw new Error('教务系统没有返回课表数据：登录状态可能已失效，' +
                    '请在页面里重新登录后再点「提取课表」');
            });
        }, function () {
            throw new Error('连不上教务系统：登录状态可能已失效，请在页面里重新登录后再点「提取课表」');
        }).then(function (json) {
            var rows = rowsOf(json);
            if (rows) return { code: code, rows: rows };
            var message = text(json && json.msg);
            return { code: code, rows: [], message: message || '接口没有返回课表数据' };
        });
    }

    // 先取推算出来的当前学期；它给不出数据（还没排课 / 推算错了）就退到上一个学期。
    // 两个都没有才报错 —— 报错信息里带上教务自己说的话，便于用户反馈。
    function fetchWithFallback(codes, xhid) {
        var attempted = [];
        var lastMessage = '';

        function attempt(index) {
            return fetchTerm(codes[index], xhid).then(function (result) {
                attempted.push(result.code);
                if (result.rows.length) {
                    return { code: result.code, attempts: attempted, rows: result.rows };
                }
                lastMessage = result.message || lastMessage;
                if (index + 1 < codes.length) return attempt(index + 1);
                throw new Error('教务系统没有返回课表数据（已尝试 ' + attempted.join('、') + '）' +
                    (lastMessage ? '：' + lastMessage : '') +
                    '。请确认已经登录，并且这一学期已经排了课');
            });
        }

        return attempt(0);
    }

    var xhid = studentId();
    if (!xhid) {
        return Promise.reject(new Error('没能在页面上找到学生标识（#encodeId）：' +
            '请先在教务系统里打开课表查询页面，再点「提取课表」'));
    }

    var current = currentTerm(new Date());
    var codes = [current.code, previousTerm(current).code];

    return askCampus().then(function (campus) {
        return fetchWithFallback(codes, xhid).then(function (result) {
            return JSON.stringify({
                campus: campus || null,
                term: { code: result.code, attempts: result.attempts },
                rows: result.rows
            });
        });
    });
})()
