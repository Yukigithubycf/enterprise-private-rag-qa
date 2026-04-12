/** Default theme settings */
export const themeSettings: App.Theme.ThemeSetting = {
  themeScheme: 'light',
  grayscale: false,
  colourWeakness: false,
  recommendColor: false,
  themeColor: '#123B5D',
  otherColor: { info: '#355C7D', success: '#5E7A5B', warning: '#A67C52', error: '#A14B45' },
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
  watermark: { visible: false, text: '文渊知库 ScholarVault' },
  tokens: {
    light: {
      colors: {
        container: 'rgb(255, 252, 246)',
        layout: 'rgb(246, 243, 238)',
        inverted: 'rgb(18, 59, 93)',
        'base-text': 'rgb(31, 41, 55)'
      },
      boxShadow: {
        header: '0 1px 2px rgb(18, 59, 93, 0.06)',
        sider: '2px 0 10px 0 rgb(51, 70, 86, 0.06)',
        tab: '0 1px 2px rgb(18, 59, 93, 0.06)'
      }
    },
    dark: {
      colors: {
        container: 'rgb(26, 33, 40)',
        layout: 'rgb(18, 24, 30)',
        inverted: 'rgb(230, 224, 213)',
        'base-text': 'rgb(229, 232, 235)'
      },
      boxShadow: {
        header: '0 1px 2px rgb(0, 0, 0, 0.28)',
        sider: '2px 0 10px 0 rgb(0, 0, 0, 0.2)',
        tab: '0 1px 2px rgb(0, 0, 0, 0.22)'
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
