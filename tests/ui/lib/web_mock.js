// A web that answers the built-in web lookup (no agent): a Bing result page with one direct and one wrapped address, and the page of the first result.
// install() adds aiRequest to window.AndroidBridge (call it after the bridge exists) and records every request address in window.__reqs.
exports.install = function () {
  const b = window.AndroidBridge || (window.AndroidBridge = {});
  window.__reqs = [];
  const li = (href, title, sn) => '<li class="b_algo" data-id iid=SERP.1><div class="b_algoheader"><a href="' + href + '" h="ID=SERP,1.2"><h2>' + title + '</h2></a></div><div class="b_caption"><p class="b_lineclamp3">' + sn + '</p></div></li>';
  const BING = '<html><body><ol id="b_results">'
    + li('https://example.org/perm', '<strong>READ_PHONE_STATE</strong> permission - Example', 'Allows read only access to phone state.')
    + li('https://www.bing.com/ck/a?!&&p=abc&u=a1aHR0cHM6Ly9kZXZlbG9wZXIuYW5kcm9pZC5jb20vcmVmZXJlbmNlL2FuZHJvaWQvTWFuaWZlc3QucGVybWlzc2lvbg&ntb=1', 'Manifest.permission - Android Developers', 'Constants for permissions.')
    + '</ol></body></html>';
  const PAGE = '<html><body><nav>Menu Menu Menu Menu Menu Menu Menu Menu Menu Menu Menu Menu</nav><main><p>READ_PHONE_STATE allows read only access to phone state, including the current cellular network information and the status of any ongoing calls.</p>'
    + '<p>Some other paragraph that is long enough to be a block but says nothing about the thing that was searched for in this test page.</p></main><script>var READ_PHONE_STATE = 1;</script></body></html>';
  b.aiRequest = function (id, specJson) {
    const spec = JSON.parse(specJson); window.__reqs.push(spec.url);
    let body = '{}', status = 200;
    if (/bing\.com\/search/.test(spec.url)) body = BING;
    else if (/^https:\/\/example\.org\//.test(spec.url)) body = PAGE;
    else if (/wikipedia\.org/.test(spec.url)) body = JSON.stringify({ query: { search: [{ title: 'Android (operating system)', snippet: 'Android is an <span class="searchmatch">operating</span> system' }] } });
    else { status = 404; body = 'no'; }
    setTimeout(() => window.onAiDone && window.onAiDone(id, { status: status, body: body, headers: {}, error: '' }), 5);
    return 'started';
  };
};
