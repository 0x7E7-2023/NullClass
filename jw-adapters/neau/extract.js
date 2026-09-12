(function () {
    // 东北农业大学 教务适配器（URP 综合教务系统）—— 第一步：取数
    //
    // 移植自 shiguang_warehouse 的 NEAU/NEAU_01.js
    //   https://github.com/XingHeYuZhuan/shiguang_warehouse  （MIT，上游作者 dezige131）
    //
    // 取数方式：一个同源 GET（URP 的课表 JSON 接口），不做 DOM 抓取。
    //   /student/courseSelect/thisSemesterCurriculum/ajaxStudentSchedule/callback
    //   返回 { xkxx: [ { 课程ID: { courseName, attendClassTeacher, timeAndPlaceList[…] } } ],
    //          jcsjbs: [ { jc, kssj, jssj } ] }
    //
    // 本脚本只把这份原始 JSON 原样交出去（外加抓取日期与当前页地址），
    // 周次解析、课程拼装全在 parse.js —— 那边 CI 里能真跑。
    //
    // 关于请求的域（校外走 WebVPN 的场景）：
    //   东农的教务在 WebVPN 网关后面，教务处公布的学生端入口是
    //   https://zhjwxs.webvpn.neau.edu.cn（网关把内网站点重写成 <站点>.webvpn.neau.edu.cn
    //   这种子域，另有带端口后缀的写法 <站点>-443.webvpn.neau.edu.cn）。
    //   用户先登录 https://webvpn.neau.edu.cn/，再打开教务系统 —— 页面本身就在那个子域上，
    //   所以这条**相对路径**请求仍然是同源，脚本里不硬编码任何主机名，校内直连
    //   （zhjwxs.neau.edu.cn）也走同一条路径。
    //   manifest 的 allowHosts 写 *.neau.edu.cn，把网关域与它下面的重写子域一起覆盖。
    var API_PATH = '/student/courseSelect/thisSemesterCurriculum/ajaxStudentSchedule/callback';

    // 少数 WebVPN（深信服那套）把内网站点放在 /http/<加密串>/… 前缀下，
    // 这时绝对化的相对路径会把这个前缀丢掉（请求落到网关根上，不是教务）。
    // 网瑞达式的子域重写没有前缀，这里不会命中。
    function vpnBase() {
        var match = /^\/(https?)\/([A-Za-z0-9_.-]+)\//.exec(window.location.pathname);
        return match ? '/' + match[1] + '/' + match[2] : '';
    }

    function pad2(n) {
        return (n < 10 ? '0' : '') + n;
    }

    // 本地日期（不能用 UTC：北京时间早上 8 点前 toISOString 会退到前一天，
    // 学期名与开学日推算都会被带偏一天）
    function localDate() {
        var now = new Date();
        return now.getFullYear() + '-' + pad2(now.getMonth() + 1) + '-' + pad2(now.getDate());
    }

    function requestSchedule(url) {
        return fetch(url, { method: 'GET', credentials: 'same-origin' }).then(function (response) {
            if (!response.ok) {
                if (response.status === 403 || response.status === 404) {
                    throw new Error('教务系统返回 HTTP ' + response.status +
                        '：当前页面可能不是教务系统。请先在 WebVPN 里打开「教务系统（学生端）」，' +
                        '停在课表页，再点「提取课表」');
                }
                throw new Error('教务系统返回 HTTP ' + response.status + '，请稍后重试');
            }
            return response.text();
        }, function () {
            throw new Error('连不上教务系统：登录状态可能已失效，请在 WebVPN / 教务系统里' +
                '重新登录后再点「提取课表」');
        });
    }

    function parseBody(body) {
        try {
            return JSON.parse(body);
        } catch (e) {
            // 未登录时教务通常 302 到登录页，fetch 跟过去拿回的是 HTML
            throw new Error('教务系统没有返回课表数据（拿到的不是 JSON）：' +
                '登录状态可能已失效，或当前页面不是教务系统学生端的课表页');
        }
    }

    return requestSchedule(vpnBase() + API_PATH).then(parseBody).then(function (raw) {
        return JSON.stringify({
            source: 'urp-ajaxStudentSchedule',
            apiPath: vpnBase() + API_PATH,
            pageUrl: String(window.location.href),
            fetchedDate: localDate(),
            raw: raw
        });
    });
})()
