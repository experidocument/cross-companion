// Injected into the Google results page shown in the Clue tab. Strips search chrome,
// finds the "AI Overview" block, hides everything around it and reports back to the app
// through the ClueBridge interface. Google's markup changes often, so everything here is
// heuristic: text matching plus "how much does the parent add" container detection.
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
    'html,body{margin:0 !important;padding:0 !important;background:#fff !important}';

  var style = document.createElement('style');
  style.textContent = CHROME_CSS;
  (document.head || document.documentElement).appendChild(style);

  var state = { found: false, gaveUp: false, expanded: false, started: Date.now() };

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

  // Climb while the parent adds little beyond the overview itself; stop once it starts
  // swallowing other results.
  function findContainer(heading) {
    var el = heading;
    while (el.parentElement && el.parentElement !== document.body) {
      var parent = el.parentElement;
      if (parent.id === 'rso' || parent.id === 'center_col' || parent.id === 'search') break;
      if (textLen(parent) > textLen(el) * 1.25 + 300) break;
      el = parent;
    }
    return el;
  }

  function isolate(container) {
    var node = container;
    while (node && node !== document.body && node.parentElement) {
      var siblings = node.parentElement.children;
      for (var i = 0; i < siblings.length; i++) {
        var s = siblings[i];
        if (s !== node && s.tagName !== 'SCRIPT' && s.tagName !== 'STYLE') {
          s.style.setProperty('display', 'none', 'important');
        }
      }
      node.style.setProperty('margin-top', '0', 'important');
      node.style.setProperty('padding-top', '0', 'important');
      node = node.parentElement;
    }
    container.style.setProperty('padding', '4px 12px 24px', 'important');
    window.scrollTo(0, 0);
  }

  function expand(container) {
    if (state.expanded) return;
    var buttons = container.querySelectorAll('[role="button"], button, div[aria-expanded="false"]');
    for (var i = 0; i < buttons.length; i++) {
      var t = (buttons[i].innerText || '').trim().toLowerCase();
      if (t === 'show more' || t === 'show all') {
        state.expanded = true;
        buttons[i].click();
        return;
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
    expand(container);
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
    if (Date.now() - state.started > 10000) clearInterval(tick);
  }, 1000);

  window.__clueIsolate = { scan: scan };
  scan();
})();
