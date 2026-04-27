/** Default theme settings */
export const themeSettings: App.Theme.ThemeSetting = {
  themeScheme: 'light',
  grayscale: false,
  colourWeakness: false,
  recommendColor: false,
  themeColor: '#155EEF',
  otherColor: { info: '#0E7490', success: '#16805D', warning: '#B7791F', error: '#C2413D' },
  isInfoFollowPrimary: false,
  resetCacheStrategy: 'close',
  layout: { mode: 'vertical', scrollMode: 'content', reverseHorizontalMix: false },
  page: { animate: true, animateMode: 'fade-slide' },
  header: { height: 56, breadcrumb: { visible: false, showIcon: true }, multilingual: { visible: false } },
  tab: { visible: false, cache: true, height: 44, mode: 'chrome' },
  fixedHeaderAndTab: true,
  sider: {
    inverted: false,
    width: 180,
    collapsedWidth: 64,
    mixWidth: 90,
    mixCollapsedWidth: 64,
    mixChildMenuWidth: 200
  },
  footer: { visible: false, fixed: false, height: 48, right: true },
  watermark: { visible: false, text: '企业知识中枢 Enterprise Knowledge Hub' },
  tokens: {
    light: {
      colors: {
        container: 'rgb(255, 255, 255)',
        layout: 'rgb(244, 247, 251)',
        inverted: 'rgb(12, 31, 54)',
        'base-text': 'rgb(20, 27, 38)'
      },
      boxShadow: {
        header: '0 1px 0 rgb(15, 23, 42, 0.06)',
        sider: '1px 0 0 0 rgb(15, 23, 42, 0.08)',
        tab: '0 1px 0 rgb(15, 23, 42, 0.06)'
      }
    },
    dark: {
      colors: {
        container: 'rgb(18, 24, 33)',
        layout: 'rgb(12, 17, 24)',
        inverted: 'rgb(226, 233, 242)',
        'base-text': 'rgb(229, 234, 240)'
      },
      boxShadow: {
        header: '0 1px 0 rgb(0, 0, 0, 0.28)',
        sider: '1px 0 0 0 rgb(255, 255, 255, 0.08)',
        tab: '0 1px 0 rgb(0, 0, 0, 0.22)'
      }
    }
  }
};

/**
 * Override theme settings
 *
 * If publish new version, use `overrideThemeSettings` to override certain theme settings
 */
export const overrideThemeSettings: Partial<App.Theme.ThemeSetting> = {};
