// Sets the theme before the first paint, so a dark-theme reload doesn't flash white. A plain script in <head>, not a
// module: modules run after the page is parsed. Light is the default; the choice is remembered per browser.
(function () {
  var theme = 'light';
  try {
    if (localStorage.getItem('payflo.theme') === 'dark') theme = 'dark';
  } catch (e) {
    // Storage is unavailable (a private window): the default theme, not remembered.
  }
  document.documentElement.dataset.theme = theme;
})();
