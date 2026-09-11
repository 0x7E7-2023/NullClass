(function () {
    // 通用适配器（第一步）：把页面量成「文字 + 坐标」。
    //
    // 不做任何页面结构假设——不认 class、不认 id、不认用了哪个框架，只量文本节点和它们的矩形。
    // 为什么能量：课表的行列语义是**位置**决定的（表头是星期、首列是节次），而这些位置在 DOM 里
    // 就是现成的（getBoundingClientRect），文字也是精确的——比截图 + OCR 更准也更稳。
    // 课表是图片 / 画布画出来的那种（页面里根本没有课表文字），这里只报候选图片，
    // 由第二步（parse.js）决定是否退回 OCR。
    //
    // 输出两类文本框：整页的，以及「最像课表的那块」的（容器内坐标）。挑哪一份交给 parse.js，
    // 因为那一步不碰 DOM，能在 CI 里用 Rhino 跑真实回归。
    var MAX_BOXES = 20000;
    var MAX_TEXT = 120;
    var MIN_IMAGE_WIDTH = 400;
    var MIN_IMAGE_HEIGHT = 300;

    var DAY_RE = /(周[一二三四五六日天]|星期[一二三四五六日天]|礼拜[一二三四五六日天])/;

    function clean(value) {
        return String(value).replace(/\s+/g, ' ').trim();
    }

    function scrollOf(view) {
        if (!view) return { x: 0, y: 0 };
        return { x: view.scrollX || view.pageXOffset || 0, y: view.scrollY || view.pageYOffset || 0 };
    }

    // 文本节点的矩形。用 Range 而不是父元素：一格里的课名/教师/周次/教室通常是四个文本节点，
    // 量父元素会把它们糊成一块，量各自的范围才能还原出四行。
    function rectOf(node, doc) {
        try {
            var range = doc.createRange();
            range.setStart(node, 0);
            range.setEnd(node, node.nodeValue.length);
            var rect = range.getBoundingClientRect();
            if (!rect || rect.width <= 0 || rect.height <= 0) return null;
            return rect;
        } catch (e) {
            return null;
        }
    }

    function walk(parent, doc, offsetX, offsetY, out) {
        var nodes = parent.childNodes;
        for (var i = 0; i < nodes.length && out.length < MAX_BOXES; i++) {
            var node = nodes[i];
            if (node.nodeType === 3) {
                var text = clean(node.nodeValue);
                if (!text) continue;
                var rect = rectOf(node, doc);
                if (!rect) continue;
                out.push({
                    text: text.length > MAX_TEXT ? text.substring(0, MAX_TEXT) : text,
                    x: Math.round(rect.left + offsetX),
                    y: Math.round(rect.top + offsetY),
                    w: Math.round(rect.width),
                    h: Math.round(rect.height)
                });
                continue;
            }
            if (node.nodeType !== 1) continue;
            var tag = node.tagName;
            if (tag === 'SCRIPT' || tag === 'STYLE' || tag === 'NOSCRIPT' || tag === 'HEAD') continue;
            if (tag === 'IFRAME' || tag === 'FRAME') {
                // 教务系统把课表塞进 iframe 的很常见，同源的也要量到（跨域读不到，跳过）
                try {
                    var inner = node.contentDocument;
                    if (inner && inner.body) {
                        var frame = node.getBoundingClientRect();
                        var innerScroll = scrollOf(inner.defaultView);
                        walk(
                            inner.body,
                            inner,
                            offsetX + frame.left + innerScroll.x,
                            offsetY + frame.top + innerScroll.y,
                            out
                        );
                    }
                } catch (e) {
                    // 跨域 iframe：读不到就算了
                }
                continue;
            }
            walk(node, doc, offsetX, offsetY, out);
            if (node.shadowRoot) walk(node.shadowRoot, doc, offsetX, offsetY, out);
        }
    }

    function countDays(text) {
        var rest = text;
        var count = 0;
        var index = rest.search(DAY_RE);
        while (index >= 0 && count < 8) {
            count++;
            rest = rest.substring(index + 1);
            index = rest.search(DAY_RE);
        }
        return count;
    }

    function countChar(text, ch) {
        var count = 0;
        var index = text.indexOf(ch);
        while (index >= 0) {
            count++;
            index = text.indexOf(ch, index + 1);
        }
        return count;
    }

    // 最像课表的那块：含 ≥4 个星期标签、≥4 个「周」字，且外接框最小的元素。
    // 祖先元素含同样的文字但框更大，所以「最小」就等于「最深」；只有表头行的话「周」字不够，会被排除。
    function pickContainer() {
        var all = document.body ? document.body.querySelectorAll('*') : [];
        var best = null;
        var bestArea = Infinity;
        for (var i = 0; i < all.length; i++) {
            var el = all[i];
            var text = el.textContent || '';
            if (text.length < 40 || text.length > 80000) continue;
            if (countDays(text) < 4 || countChar(text, '周') < 4) continue;
            var rect = el.getBoundingClientRect();
            var area = rect.width * rect.height;
            if (!(area > 0)) continue;
            if (area < bestArea) {
                bestArea = area;
                best = el;
            }
        }
        return best;
    }

    // 课表图片的候选（页面里没有课表文字时才会被采用）
    function pickImage() {
        var nodes = document.querySelectorAll('img, canvas');
        var best = null;
        var bestArea = 0;
        for (var i = 0; i < nodes.length; i++) {
            var el = nodes[i];
            var isCanvas = el.tagName === 'CANVAS';
            var width = isCanvas ? el.width : el.naturalWidth;
            var height = isCanvas ? el.height : el.naturalHeight;
            if (!(width >= MIN_IMAGE_WIDTH && height >= MIN_IMAGE_HEIGHT)) continue;
            var rect = el.getBoundingClientRect();
            if (!(rect.width > 0 && rect.height > 0)) continue;
            if (width * height > bestArea) {
                bestArea = width * height;
                best = el;
            }
        }
        if (!best) return null;
        if (best.tagName === 'CANVAS') {
            try {
                // 画布课表就直接把像素交出去（同源画布才导得出，跨域的会抛错）
                return { data: best.toDataURL('image/png'), hint: '课表画布' };
            } catch (e) {
                return null;
            }
        }
        var src = best.currentSrc || best.src;
        return src ? { url: String(src), hint: '课表图片' } : null;
    }

    var doc = document;
    var root = doc.body || doc.documentElement;
    var view = scrollOf(window);

    var pageBoxes = [];
    walk(root, doc, view.x, view.y, pageBoxes);

    var containerInfo = null;
    var container = pickContainer();
    if (container) {
        var rect = container.getBoundingClientRect();
        var containerBoxes = [];
        // 容器内坐标：两个矩形都是**视口坐标**，相减即可，不能掺进 window.scroll（掺了就整体偏移一个滚动量，
        // 而 pageWidth 是容器宽 —— 应用的「节次列在左侧 1/4」判据会因此整列落空）
        walk(container, doc, -rect.left, -rect.top, containerBoxes);
        containerInfo = {
            width: Math.round(rect.width),
            height: Math.round(rect.height),
            boxes: containerBoxes
        };
    }

    return JSON.stringify({
        url: String(location.href),
        title: String(doc.title || ''),
        page: {
            width: Math.round(Math.max(doc.documentElement.scrollWidth, root.scrollWidth)),
            height: Math.round(Math.max(doc.documentElement.scrollHeight, root.scrollHeight)),
            boxes: pageBoxes
        },
        container: containerInfo,
        image: pickImage()
    });
})()
