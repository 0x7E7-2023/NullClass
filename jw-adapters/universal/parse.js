(function () {
    // 通用适配器（第二步）：在「最像课表的那块」与「整页」两份文本框之间挑一份交给应用，
    // 两边都不像课表时退回课表图片走 OCR。
    //
    // 这一步不碰 DOM（输入是 __ncInput），所以能在 CI 里用 Rhino 跑真实回归；
    // 挑出来的文本框由应用里与 OCR 共用的表格结构层还原行列，这里不做结构判断。
    var data = JSON.parse(__ncInput);
    var MIN_BOXES = 12;
    var MIN_PERIOD_BOXES = 3;

    // 节次列的写法："3" / "3-4" / "第3-4节"（与应用的 parsePeriodLabel 同口径）
    var PERIOD_RE = /^(第)?\d{1,2}([-—~至]\d{1,2})?(节)?$/;

    function periodLike(boxes) {
        var count = 0;
        for (var i = 0; i < boxes.length; i++) {
            if (PERIOD_RE.test(boxes[i].text)) count++;
        }
        return count;
    }

    function usable(set) {
        return !!set && !!set.boxes && set.boxes.length >= MIN_BOXES &&
            periodLike(set.boxes) >= MIN_PERIOD_BOXES;
    }

    function boxesPayload(set) {
        return {
            specVersion: 1,
            kind: 'boxes',
            ocrAssisted: false,
            pageWidth: set.width,
            pageHeight: set.height,
            boxes: set.boxes
        };
    }

    var page = data.page;
    var container = data.container;
    var chosen = null;

    if (usable(container)) {
        chosen = container;
    } else if (usable(page)) {
        chosen = page;
    }
    if (chosen) {
        return JSON.stringify(boxesPayload(chosen));
    }

    // 两份都不像课表：课表很可能是画在图片/画布上的，交给应用的 OCR 链路
    if (data.image) {
        return JSON.stringify({
            specVersion: 1,
            kind: 'image',
            ocrAssisted: true,
            images: [data.image]
        });
    }

    // 连图片都没有：还是把文本框交出去——应用的对齐层能说出「缺星期表头 / 缺节次列」，
    // 比这里笼统地说一句「没找到课表」有用得多
    var fallbackSet = (container && container.boxes && container.boxes.length) ? container : page;
    if (fallbackSet && fallbackSet.boxes && fallbackSet.boxes.length) {
        return JSON.stringify(boxesPayload(fallbackSet));
    }

    throw new Error(
        '这一页里没找到课表：既没有课表文字，也没有课表图片。' +
        '请确认已经登录并打开了课表页面（不是首页或菜单页）'
    );
})()
