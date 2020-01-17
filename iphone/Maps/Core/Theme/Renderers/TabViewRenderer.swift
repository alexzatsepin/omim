import UIKit
extension TabView {
  @objc override func applyTheme() {
    if styleName.isEmpty {
      styleName = "TabView"
    }
    for style in StyleManager.instance().getStyle(styleName) {
      TabViewRenderer.render(self, style: style)
    }
  }
}

class TabViewRenderer {
  class func render(_ control: TabView, style: Style) {
    if let backgroundColor = style.backgroundColor {
      control.backgroundColor = backgroundColor
    }
    if let barTintColor = style.barTintColor {
      control.barTintColor = barTintColor
    }
    if let tintColor = style.tintColor {
      control.tintColor = tintColor
    }
    if let font = style.font, let fontColor = style.fontColor {
      control.headerTextAttributes = [.foregroundColor: fontColor,
                                      .font: font]
    }
  }
}
