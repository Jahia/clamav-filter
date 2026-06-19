// Uses `window` (not `globalThis`): the Babel browser target matrix includes Safari 12 and
// Chrome 60, which predate globalThis. window is the correct, supported global here.
import('@jahia/app-shell/bootstrap').then(res => {
    window.jahia = res;
    res.startAppShell(window.appShell.remotes, window.appShell.targetId);
});
