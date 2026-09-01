const features = [
    "castmenuhashchange",
    "clientsettings",
    "displaylanguage",
    "downloadmanagement",
    "exit",
    "externallinks",
    "filedownload",
    "fileinput",
    "htmlaudioautoplay",
    "htmlvideoautoplay",
    "multiserver",
    "physicalvolumecontrol",
    "remotecontrol",
    "subtitleappearancesettings",
    "subtitleburnsettings"
];

const plugins = [
    'NavigationPlugin',
    'ExoPlayerPlugin',
    'ExternalPlayerPlugin',
    'MediaSegmentsPlugin'
];

// Add plugin loaders
for (const plugin of plugins) {
    window[plugin] = async () => {
        const pluginDefinition = await import(`/native/${plugin}.js`);
        return pluginDefinition[plugin];
    };
}

const { deviceId, deviceName, appName, appVersion } = JSON.parse(window.NativeInterface.getDeviceInformation());
const codecCaps = JSON.parse(window.NativeInterface.getCodecCapabilities());

window.NativeShell = {
    enableFullscreen() {
        window.NativeInterface.enableFullscreen();
    },

    disableFullscreen() {
        window.NativeInterface.disableFullscreen();
    },

    openUrl(url, target) {
        window.NativeInterface.openUrl(url);
    },

    updateMediaSession(mediaInfo) {
        window.NativeInterface.updateMediaSession(JSON.stringify(mediaInfo));
    },

    hideMediaSession() {
        window.NativeInterface.hideMediaSession();
    },

    updateVolumeLevel(value) {
        window.NativeInterface.updateVolumeLevel(value);
    },

    downloadFile(downloadInfo) {
        window.NativeInterface.downloadFiles(JSON.stringify([downloadInfo]));
    },

    downloadFiles(downloadInfo) {
        window.NativeInterface.downloadFiles(JSON.stringify(downloadInfo));
    },

    openDownloadManager() {
        window.NativeInterface.openDownloadManager();
    },

    openClientSettings() {
        window.NativeInterface.openClientSettings();
    },

    selectServer() {
        window.NativeInterface.openServerSelection();
    },

    getPlugins() {
        return plugins;
    },

    async execCast(action, args, callback) {
        this.castCallbacks = this.castCallbacks || {};
        this.castCallbacks[action] = callback;
        window.NativeInterface.execCast(action, JSON.stringify(args));
    },

    async castCallback(action, keep, err, result) {
        const callbacks = this.castCallbacks || {};
        const callback = callbacks[action];
        callback && callback(err || null, result);
        if (!keep) {
            delete callbacks[action];
        }
    },

    // Safe wrapper for playback manager commands. The playback manager is not
    // available on every page and some commands throw without an active player.
    playbackCommand(command) {
        try {
            const playbackManager = window.NavigationHelper && window.NavigationHelper.playbackManager;
            if (!playbackManager || typeof playbackManager[command] !== 'function') return;
            playbackManager[command]();
        } catch (error) {
            console.error('[NativeShell] playbackCommand(' + command + ') failed:', error);
        }
    }
};

function getDeviceProfile(profileBuilder, item) {
    return profileBuilder();
}

window.NativeShell.AppHost = {
    init() {},
    getDefaultLayout() {
        return "mobile";
    },
    supports(command) {
        command = command.toLowerCase();
        if (command === "chromecast") {
            return window.NativeInterface.hasChromecast();
        }
        return features.includes(command);
    },
    getDeviceProfile,
    deviceName() {
        return deviceName;
    },
    deviceId() {
        return deviceId;
    },
    appName() {
        return appName;
    },
    appVersion() {
        return appVersion;
    },
    exit() {
        window.NativeInterface.exitApp();
    }
};

// Follow the system dark/light theme when enabled, overriding the web app's
// own theme setting. Theme switching differs between web app versions:
// - up to 10.9 loads a theme stylesheet via the #cssTheme link element
// - 10.10+ sets the data-theme attribute on the root element (MUI CSS vars)
// Both are handled here and re-applied whenever the web app changes them.
// The native side reports the system dark mode directly; WebView matchMedia
// for prefers-color-scheme does not always follow the system on all ROMs.
const isSystemDark = () => {
    try {
        return !!window.NativeInterface.isSystemDarkTheme();
    } catch (e) {
        return !!window.matchMedia('(prefers-color-scheme: dark)').matches;
    }
};
const forcedThemeColors = {
    dark: '#101010',
    light: '#fafafa'
};

const applySystemTheme = () => {
    if (!window.NativeInterface.followSystemTheme()) return;
    const theme = isSystemDark() ? 'dark' : 'light';
    // Override the stored theme so the web app picks the system theme on every
    // view change instead of reverting to its own setting (prevents flashing).
    try {
        if (localStorage.getItem('appTheme') !== theme) localStorage.setItem('appTheme', theme);
        if (localStorage.getItem('dashboardTheme') !== theme) {
            localStorage.setItem('dashboardTheme', theme);
        }
    } catch (e) {}
    // jellyfin-web up to 10.9 loads the theme as a stylesheet link
    // (themes/<id>/theme.css), often without a stable element id. The href
    // attribute may be relative, so match on "themes/" without a leading slash.
    const themeLink = document.querySelector('link[rel="stylesheet"][href*="themes/"]');
    if (themeLink) {
        const themeCssPath = 'themes/' + theme + '/theme.css';
        if (themeLink.getAttribute('href') !== themeCssPath) {
            themeLink.setAttribute('href', themeCssPath);
        }
    }
    if (document.documentElement.getAttribute('data-theme') !== theme) {
        document.documentElement.setAttribute('data-theme', theme);
    }
    document.documentElement.style.colorScheme = theme;
    const themeColor = document.getElementById('themeColor');
    if (themeColor) {
        themeColor.content = forcedThemeColors[theme];
    }
};

applySystemTheme();
// Apply the theme before the web app renders a new view, so navigation does
// not flash the dashboard theme before the system theme is re-applied.
// Capture phase runs before the web app's own viewbeforeshow listeners.
document.addEventListener('viewbeforeshow', applySystemTheme, true);
document.addEventListener('viewshow', applySystemTheme, true);
// The web app reads the theme from the server user config, which we cannot
// override, so it will keep writing its own theme stylesheet on navigation.
// A per-frame watcher reverts it within the same frame, making the flash
// imperceptible. requestAnimationFrame is paused when the page is hidden,
// which is fine since no one is looking at it then.
const watchTheme = () => {
    const link = document.querySelector('link[rel="stylesheet"][href*="themes/"]');
    if (link && window.NativeInterface.followSystemTheme()) {
        const expected = 'themes/' + (isSystemDark() ? 'dark' : 'light') + '/theme.css';
        if (link.getAttribute('href') !== expected) {
            link.setAttribute('href', expected);
        }
    }
    requestAnimationFrame(watchTheme);
};
requestAnimationFrame(watchTheme);
// Re-apply whenever the web app changes the theme itself (navigation, settings save, ...)
let themeApplyScheduled = false;
const scheduleThemeApply = () => {
    if (themeApplyScheduled) return;
    themeApplyScheduled = true;
    requestAnimationFrame(() => {
        themeApplyScheduled = false;
        applySystemTheme();
        applyAppearanceOverrides();
    });
};
new MutationObserver(scheduleThemeApply).observe(document.documentElement, {
    attributes: true,
    attributeFilter: ['data-theme']
});
new MutationObserver(scheduleThemeApply).observe(document.body, {
    childList: true,
    attributes: true,
    attributeFilter: ['href']
});

// Additional appearance overrides for the web UI that hard-codes dark colors:
// - detailRibbon (item detail header) is dark gray in all themes
// - dialogs/sheets are dark in light theme
// - cards get a soft shadow for separation in both themes
let appearanceStyleElement = null;
const applyAppearanceOverrides = () => {
    const dark = isSystemDark();
    if (!appearanceStyleElement) {
        appearanceStyleElement = document.createElement('style');
        appearanceStyleElement.id = 'nativeAppearanceOverrides';
        document.head.appendChild(appearanceStyleElement);
    }
    appearanceStyleElement.textContent = dark ? (
        // Global fallback so the page never flashes light while the theme
        // stylesheet is swapped during navigation.
        'html, body, .mainContainer, .content, .page, .dashboardContent,' +
        '.backgroundContainer, .libraryPage, .paperList{' +
        'background:#101010 !important;}' +
        '.skinHeader, .detailRibbon{background:#2a2a2a !important;color:rgba(255,255,255,0.87) !important;}' +
        '.pageTitleWithLogo.pageTitleWithDefaultLogo{' +
        'background-image:url("/native/banner-dark.png") !important;background-size:contain;background-repeat:no-repeat;background-position:left center;background-origin:content-box;}' +
        '.mainDrawer, .appDrawer, .drawerMenu, .mainDrawer.touch-menu-la, .touch-menu-la{' +
        'background:#101010 !important;}' +
        // Dashboard uses a MUI drawer which ignores the classic theme
        '.MuiDrawer-paper{background:#101010 !important;color:rgba(255,255,255,0.87) !important;}' +
        '.MuiDrawer-paper .MuiListItemText-root, .MuiDrawer-paper .MuiListItemIcon-root,' +
        '.MuiDrawer-paper .MuiTypography-root, .MuiDrawer-paper .MuiButtonBase-root' +
        '{color:rgba(255,255,255,0.87) !important;}' +
        '.MuiDrawer-paper .MuiListItem-root:hover{background:rgba(255,255,255,0.08) !important;}' +
        '.MuiDrawer-paper .Mui-selected{background:#00a4dc !important;color:#fff !important;}' +
        // The dashboard uses its own MUI theme that ignores the system theme;
        // force the top app bar, cards and popovers to dark surfaces too.
        '.MuiAppBar-root{background:#1e1e1e !important;color:rgba(255,255,255,0.87) !important;}' +
        '.MuiAppBar-root .MuiTypography-root, .MuiAppBar-root .MuiButtonBase-root,' +
        '.MuiAppBar-root .MuiIconButton-root, .MuiAppBar-root .MuiSvgIcon-root' +
        '{color:rgba(255,255,255,0.87) !important;}' +
        '.MuiPaper-root, .MuiPopover-paper, .MuiMenu-paper{background:#1e1e1e !important;color:rgba(255,255,255,0.87) !important;}' +
        '.MuiPaper-root .MuiTypography-root, .MuiPaper-root .MuiListItemText-root,' +
        '.MuiPaper-root .MuiListItemIcon-root, .MuiPaper-root .MuiButtonBase-root' +
        '{color:rgba(255,255,255,0.87) !important;}' +
        // Dashboard sidebar group headers (服务器/设备/插件/...) stay dark gray
        // in the independent MUI theme; force them light so they read on dark.
        '.MuiListSubheader-root{color:rgba(255,255,255,0.6) !important;}' +
        '.mainDrawer .navMenuOption, .mainDrawer .sidebarHeader, .mainDrawer h3,' +
        '.mainDrawer a, .mainDrawer .navMenuOptionText{' +
        'color:rgba(255,255,255,0.87) !important;}' +
        '.mainDrawer .navMenuOption:hover{background:#252528 !important;}' +
        '.mainDrawer .navMenuOption-selected{background:#00a4dc !important;color:#fff !important;}' +
        '.card{box-shadow:0 1px 4px rgba(0,0,0,0.45) !important;}'
    ) : (
        // Light theme: white header on top of the light gray content area to
        // mirror the dark theme's layered look (gray header, darker content).
        // Global fallback keeps the page light while the theme stylesheet is
        // swapped during navigation.
        'html, body, .mainContainer, .content, .page, .dashboardContent,' +
        '.libraryPage, .paperList{background:#f2f2f2 !important;}' +
        '.skinHeader{background:#ffffff !important;color:rgba(0,0,0,0.87) !important;' +
        'box-shadow:0 1px 6px rgba(0,0,0,0.12) !important;position:relative;z-index:2;}' +
        '.skinHeader .navMenuOption, .skinHeader .headerTop, .skinHeader .headerTabs,' +
        '.skinHeader .emby-tabs, .skinHeader .emby-tab,' +
        '.skinHeader .btn, .skinHeader .material-icons,' +
        '.skinHeader .txtButton, .skinHeader .buttonText' +
        '{color:rgba(0,0,0,0.87) !important;}' +
        '.skinHeader .headerButtonIcon, .skinHeader .emby-tab-button{color:rgba(0,0,0,0.87) !important;}' +
        // Dashboard drawer follows the dashboard theme regardless of the system
        // theme; force it to match the system theme in light mode.
        '.mainDrawer, .appDrawer, .drawerMenu, .mainDrawer.touch-menu-la, .touch-menu-la{' +
        'background:#ffffff !important;} ' +
        // Dashboard MUI drawer follows the light theme in light mode
        '.MuiDrawer-paper{background:#ffffff !important;color:rgba(0,0,0,0.87) !important;}' +
        '.MuiDrawer-paper .MuiListItemText-root, .MuiDrawer-paper .MuiListItemIcon-root,' +
        '.MuiDrawer-paper .MuiTypography-root, .MuiDrawer-paper .MuiButtonBase-root' +
        '{color:rgba(0,0,0,0.87) !important;}' +
        '.MuiDrawer-paper .MuiListItem-root:hover{background:rgba(0,0,0,0.04) !important;}' +
        '.MuiDrawer-paper .Mui-selected{background:#00a4dc !important;color:#fff !important;}' +
        // Dashboard app bar / cards must get a light surface too, mirroring the
        // dark-theme overrides; otherwise they stay transparent in light mode
        // and the dashboard content blends into the page background.
        '.MuiAppBar-root{background:#ffffff !important;color:rgba(0,0,0,0.87) !important;}' +
        '.MuiAppBar-root .MuiTypography-root, .MuiAppBar-root .MuiButtonBase-root,' +
        '.MuiAppBar-root .MuiIconButton-root, .MuiAppBar-root .MuiSvgIcon-root' +
        '{color:rgba(0,0,0,0.87) !important;}' +
        '.MuiPaper-root, .MuiPopover-paper, .MuiMenu-paper{background:#ffffff !important;color:rgba(0,0,0,0.87) !important;}' +
        '.MuiPaper-root .MuiTypography-root, .MuiPaper-root .MuiListItemText-root,' +
        '.MuiPaper-root .MuiListItemIcon-root, .MuiPaper-root .MuiButtonBase-root' +
        '{color:rgba(0,0,0,0.87) !important;}' +
        '.MuiListSubheader-root{color:rgba(0,0,0,0.6) !important;}' +
        '.mainDrawer .navMenuOption, .mainDrawer .sidebarHeader, .mainDrawer h3,' +
        '.mainDrawer a, .mainDrawer .navMenuOptionText{' +
        'color:rgba(0,0,0,0.87) !important;}' +
        '.mainDrawer .navMenuOption:hover{background:#f2f2f2 !important;}' +
        '.mainDrawer .navMenuOption-selected{background:#00a4dc !important;color:#fff !important;}' +
        '.backgroundContainer{background:#f2f2f2 !important;}' +
        '.detailRibbon{background:#ffffff !important;color:rgba(0,0,0,0.87) !important;' +
        'box-shadow:0 1px 6px rgba(0,0,0,0.08) !important;}' +
        // The server ships a white banner that is unreadable on the light header;
        // use the bundled theme banners instead (provided via /native/).
        '.pageTitleWithLogo.pageTitleWithDefaultLogo{' +
        'background-image:url("/native/banner-light.png") !important;background-size:contain;background-repeat:no-repeat;background-position:left center;background-origin:content-box;}' +
        '.dialog, .sheet, .dlg, [data-role="dialog"]{background:#ffffff !important;color:rgba(0,0,0,0.87) !important;}' +
        '.dialog h1, .dialog h2, .dialog h3, .dialog .formDialogHeaderTitle,' +
        '.sheet h1, .sheet h2, .sheet h3{color:rgba(0,0,0,0.87) !important;}' +
        '.dialog .inputLabel, .sheet .inputLabel,' +
        '.dialog label, .sheet label{color:rgba(0,0,0,0.6) !important;}' +
        '.card{box-shadow:0 1px 4px rgba(0,0,0,0.14) !important;}'
    );
};
applyAppearanceOverrides();

// Add a "Follow system" option to the theme selects on the web settings page
// (user theme #selectTheme and dashboard theme #selectDashboardTheme) and
// sync the app preference with the selection.
const THEME_AUTO_VALUE = 'auto';
const THEME_AUTO_LABEL = 'Follow system (\u8ddf\u968f\u7cfb\u7edf)';

const addAutoThemeOption = (select) => {
    if (!select.querySelector('option[value="' + THEME_AUTO_VALUE + '"]')) {
        const option = document.createElement('option');
        option.value = THEME_AUTO_VALUE;
        option.textContent = THEME_AUTO_LABEL;
        select.appendChild(option);
    }
};

const syncThemeSelects = () => {
    const followSystem = window.NativeInterface.followSystemTheme();
    ['#selectTheme', '#selectDashboardTheme'].forEach((selector) => {
        const select = document.querySelector(selector);
        if (!select) return;
        addAutoThemeOption(select);
        if (followSystem && select.value !== THEME_AUTO_VALUE) {
            select.value = THEME_AUTO_VALUE;
        }
    });
};

// Settings views are rendered dynamically, so watch for their appearance.
let themeSyncScheduled = false;
const scheduleThemeSync = () => {
    if (themeSyncScheduled) return;
    themeSyncScheduled = true;
    requestAnimationFrame(() => {
        themeSyncScheduled = false;
        syncThemeSelects();
    });
};
new MutationObserver(scheduleThemeSync).observe(document.body, { childList: true, subtree: true });
syncThemeSelects();

document.addEventListener('change', (event) => {
    const target = event.target;
    if (!target || (target.id !== 'selectTheme' && target.id !== 'selectDashboardTheme')) return;
    if (target.value === THEME_AUTO_VALUE) {
        window.NativeInterface.setFollowSystemTheme(true);
        applySystemTheme();
    } else {
        window.NativeInterface.setFollowSystemTheme(false);
    }
});
