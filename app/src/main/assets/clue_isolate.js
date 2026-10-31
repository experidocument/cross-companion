// Injected into the Google results page shown in the Clue tab. Strips search chrome,
// finds the "AI Overview" block, hides everything around it and reports back to the app
// through the ClueBridge interface. Google's markup changes often, so everything here is
// heuristic: text matching plus result-list-based container detection.
(function () {
  // Google's "unusual traffic" check: leave it untouched so the user can complete it.
  if (location.pathname.indexOf('/sorry') === 0) { ClueBridge.onChallenge(); return; }
  if (window.__clueIsolate) { window.__clueIsolate.scan(); return; }

  var CHROME_CSS = [
    'header', '#searchform', '#sfcnt', '#sfooter', 'footer', '#footcnt', '#fbar', '#botstuff',
    '#bottomads', '#tads', '#taw', '#hdtb', '#hdtbMenus', '#appbar', '#top_nav', '#slim_appbar',
    '#gb', '#msc', '#lb', '.sfbg', '[role="navigation"]', '[aria-label="Search modes"]',
    'g-scrolling-carousel', '#bres', '[data-initq]'
  ].join(',') + '{display:none !important}' +
    // No background override: Google follows the phone's dark mode, and forcing white
    // would leave white text on a white page.
    'html,body{margin:0 !important;padding:0 !important}';

  var style = document.createElement('style');
  style.textContent = CHROME_CSS;
  (document.head || document.documentElement).appendChild(style);

  var state = { found: false, gaveUp: false, started: Date.now() };

  function textLen(el) { return (el.innerText || el.textContent || '').length; }

  function findHeading() {
    var xp = "//*[normalize-space(text())='AI Overview' or normalize-space(text())='AI overview']";
    var r = document.evaluate(xp, document.body, null, XPathResult.ORDERED_NODE_SNAPSHOT_TYPE, null);
    for (var i = 0; i < r.snapshotLength; i++) {
      var n = r.snapshotItem(i);
      if (n.offsetParent !== null || n.getClientRects().length) return n;
    }
    return null;
  }

  var RESULT_LISTS = { rso: 1, center_col: 1, search: 1, rcnt: 1, main: 1 };

  function hasOrganicResult(el, except) {
    var titles = el.querySelectorAll('a h3, [data-q]');
    for (var i = 0; i < titles.length; i++) if (!except.contains(titles[i])) return true;
    return false;
  }

  // The overview is the block that sits directly inside Google's result list (#rso). The
  // "AI Overview" label lives in a small header next to the streamed answer, so climbing
  // by text size stops too early and hides the answer itself.
  function findContainer(heading) {
    var el = heading;
    while (el.parentElement && el.parentElement !== document.body) {
      var parent = el.parentElement;
      if (RESULT_LISTS[parent.id]) break;
      // No known list id: stop before swallowing an ordinary result or "People also ask".
      if (textLen(el) > 200 && hasOrganicResult(parent, el)) break;
      el = parent;
    }
    return el;
  }

  var hidden = [];

  function isolate(container) {
    // Undo the previous pass first: while the answer streams in, the container can change.
    hidden.forEach(function (s) { s.style.removeProperty('display'); });
    hidden = [];
    var node = container;
    while (node && node !== document.body && node.parentElement) {
      var siblings = node.parentElement.children;
      for (var i = 0; i < siblings.length; i++) {
        var s = siblings[i];
        if (s !== node && s.tagName !== 'SCRIPT' && s.tagName !== 'STYLE') {
          s.style.setProperty('display', 'none', 'important');
          hidden.push(s);
        }
      }
      node.style.setProperty('margin-top', '0', 'important');
      node.style.setProperty('padding-top', '0', 'important');
      node = node.parentElement;
    }
    container.style.setProperty('padding', '4px 12px 24px', 'important');
    window.scrollTo(0, 0);
  }

  // The overview is collapsed to a fixed height behind "Show more", which now opens a
  // full-screen AI Mode chat instead of expanding in place. Lift the height caps and hide
  // the button so the whole answer scrolls inside the card.
  function unclamp(container) {
    var divs = container.querySelectorAll('div');
    for (var i = 0; i < divs.length; i++) {
      var d = divs[i];
      if (getComputedStyle(d).maxHeight.slice(-2) === 'px' && d.scrollHeight > d.clientHeight + 4) {
        d.style.setProperty('max-height', 'none', 'important');
        d.style.setProperty('overflow', 'visible', 'important');
      }
      if (/^show (more|all)$/i.test((d.innerText || '').trim()) && d.getBoundingClientRect().height < 80) {
        d.style.setProperty('display', 'none', 'important');
      }
    }
  }

  function scan() {
    var heading = findHeading();
    if (!heading) {
      if (!state.gaveUp && Date.now() - state.started > 9000) {
        state.gaveUp = true;
        console.log('clue_isolate: no overview; title=' + document.title + ' url=' + location.href +
          ' text=' + (document.body.innerText || '').slice(0, 400).replace(/\s+/g, ' '));
        ClueBridge.onNotFound();
      }
      return;
    }
    var container = findContainer(heading);
    isolate(container);
    unclamp(container);
    if (!state.found) {
      state.found = true;
      ClueBridge.onFound();
    }
  }

  var pending = null;
  var observer = new MutationObserver(function () {
    if (pending) return;
    pending = setTimeout(function () { pending = null; scan(); }, 250);
  });
  observer.observe(document.documentElement, { childList: true, subtree: true });
  // Overviews stream in; keep re-isolating for a while, then stop observing.
  setTimeout(function () { observer.disconnect(); scan(); }, 20000);
  var tick = setInterval(function () {
    scan();
    if (Date.now() - state.started > 20000) clearInterval(tick);
  }, 1000);

  window.__clueIsolate = { scan: scan };
  scan();
})();
