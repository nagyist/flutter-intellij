/*
 * Copyright 2018 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
/*
 * Copyright 2000-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.flutter.view;

import com.intellij.ide.BrowserUtil;
import com.intellij.ide.ui.UISettings;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.*;
import com.intellij.ui.paint.EffectPainter;
import com.intellij.util.ui.JBInsets;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.intellij.lang.annotations.JdkConstants;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.accessibility.Accessible;
import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRole;
import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.tree.TreeCellRenderer;
import java.awt.*;
import java.awt.font.FontRenderContext;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.text.AttributedCharacterIterator;
import java.text.AttributedString;
import java.text.CharacterIterator;
import java.util.*;
import java.util.List;

/**
 * This is high performance Swing component which represents
 * colored text with multiple icons. The text consists of fragments. Each
 * text fragment has its own color (foreground) and font style.
 *
 * @author Vladimir Kondratyev
 */
@SuppressWarnings({"NonPrivateFieldAccessedInSynchronizedContext", "FieldAccessedSynchronizedAndUnsynchronized"})
public class MultiIconSimpleColoredComponent extends JComponent implements Accessible, ColoredTextContainer {
  static class PositionedIcon {
    final Icon icon;
    final int index;

    public PositionedIcon(Icon icon, int index) {
      this.icon = icon;
      this.index = index;
    }
  }

  private static final Logger LOG = Logger.getInstance(MultiIconSimpleColoredComponent.class);

  public static final Color SHADOW_COLOR = new JBColor(new Color(250, 250, 250, 140), Gray._0.withAlpha(50));
  public static final Color STYLE_SEARCH_MATCH_BACKGROUND = SHADOW_COLOR; //api compatibility
  public static final int FRAGMENT_ICON = -100;

  private final List<String> myFragments;
  private final List<TextLayout> myLayouts;
  private final List<PositionedIcon> myIcons;
  private Font myLayoutFont;
  private final List<SimpleTextAttributes> myAttributes;

  private List<Object> myFragmentTags = null;
  private HashMap myFragmentAlignment;

  /**
   * Internal padding
   */
  private Insets myIpad;
  /**
   * Gap between icon and text. It is used only if icon is defined.
   */
  protected int myIconTextGap;
  /**
   * Defines whether the focus border around the text is painted or not.
   * For example, text can have a border if the component represents a selected item
   * in focused JList.
   */
  private boolean myPaintFocusBorder;
  /**
   * Defines whether the focus border around the text extends to icon or not
   */
  private boolean myFocusBorderAroundIcon;
  /**
   * This is the border around the text. For example, text can have a border
   * if the component represents a selected item in a focused JList.
   * Border can be <code>null</code>.
   */
  private Border myBorder;

  private int myMainTextLastIndex = -1;

  private final HashMap myFragmentPadding;

  @JdkConstants.HorizontalAlignment private int myTextAlign = SwingConstants.LEFT;

  private boolean myIconOpaque = false;

  private boolean myAutoInvalidate = !(this instanceof TreeCellRenderer);

  private boolean myTransparentIconBackground;

  public MultiIconSimpleColoredComponent() {
    myFragments = new ArrayList<>(3);
    myLayouts = new ArrayList<>(3);
    myAttributes = new ArrayList<>(3);
    myIcons = new ArrayList<>(3);
    myIpad = new JBInsets(1, 2, 1, 2);
    myIconTextGap = JBUI.scale(2);
    myBorder = new MyBorder();
    myFragmentPadding = new HashMap(10);
    myFragmentAlignment = new HashMap(10);
    setOpaque(true);
    updateUI();
  }

  @Override
  public void updateUI() {
    UISettings.setupComponentAntialiasing(this);
  }

  @NotNull
  public ColoredIterator iterator() {
    return new MyIterator();
  }

  @NotNull
  public final MultiIconSimpleColoredComponent append(@NotNull String fragment) {
    append(fragment, SimpleTextAttributes.REGULAR_ATTRIBUTES);
    return this;
  }

  /**
   * Appends string fragments to existing ones. Appended string
   * will have specified <code>attributes</code>.
   *
   * @param fragment   text fragment
   * @param attributes text attributes
   */
  @Override
  public final void append(@NotNull final String fragment, @NotNull final SimpleTextAttributes attributes) {
    append(fragment, attributes, myMainTextLastIndex < 0);
  }

  /**
   * Appends text fragment and sets it's end offset and alignment.
   * See SimpleColoredComponent#appendTextPadding for details
   *
   * @param fragment   text fragment
   * @param attributes text attributes
   * @param padding    end offset of the text
   * @param align      alignment between current offset and padding
   */
  public final void append(@NotNull final String fragment,
                           @NotNull final SimpleTextAttributes attributes,
                           int padding,
                           @JdkConstants.HorizontalAlignment int align) {
    append(fragment, attributes, myMainTextLastIndex < 0);
    appendTextPadding(padding, align);
  }

  /**
   * Appends string fragments to existing ones. Appended string
   * will have specified <code>attributes</code>.
   *
   * @param fragment   text fragment
   * @param attributes text attributes
   * @param isMainText main text of not
   */
  public void append(@NotNull final String fragment, @NotNull final SimpleTextAttributes attributes, boolean isMainText) {
    _append(fragment, attributes, isMainText);
    revalidateAndRepaint();
  }

  private synchronized void _append(@NotNull final String fragment, @NotNull final SimpleTextAttributes attributes, boolean isMainText) {
    myFragments.add(fragment);
    myAttributes.add(attributes);
    if (isMainText) {
      myMainTextLastIndex = myFragments.size() - 1;
    }
  }

  void revalidateAndRepaint() {
    if (myAutoInvalidate) {
      revalidate();
    }

    repaint();
  }

  @Override
  public void append(@NotNull final String fragment, @NotNull final SimpleTextAttributes attributes, Object tag) {
    _append(fragment, attributes, tag);
    revalidateAndRepaint();
  }

  /**
   * Adds the icon at the beginning of the line unlike addIcon, which adds the
   * icon after the already appended text.
   */
  @Override
  public void setIcon(@Nullable Icon icon) {
    assert (myIcons.isEmpty());
    if (icon == null) {
      return;
    }

    myIcons.add(new PositionedIcon(icon, 0));
  }

  private synchronized void _append(String fragment, SimpleTextAttributes attributes, Object tag) {
    append(fragment, attributes);
    if (myFragmentTags == null) {
      myFragmentTags = new ArrayList<>();
    }
    while (myFragmentTags.size() < myFragments.size() - 1) {
      myFragmentTags.add(null);
    }
    myFragmentTags.add(tag);
  }

  /**
   * fragment width isn't a right name, it is actually a padding
   *
   * @deprecated remove in IDEA 16
   */
  @Deprecated
  public synchronized void appendFixedTextFragmentWidth(int width) {
    appendTextPadding(width);
  }

  public synchronized void appendTextPadding(int padding) {
    appendTextPadding(padding, SwingConstants.LEFT);
  }

  /**
   * @param padding end offset that will be set after drawing current text fragment
   * @param align   alignment of the current text fragment, if it is SwingConstants.RIGHT
   *                or SwingConstants.TRAILING then the text fragment will be aligned to the right at
   *                the padding, otherwise it will be aligned to the left
   */
  public synchronized void appendTextPadding(int padding, @JdkConstants.HorizontalAlignment int align) {
    final int alignIndex = myFragments.size() - 1;
    myFragmentPadding.put(alignIndex, padding);
    myFragmentAlignment.put(alignIndex, align);
  }

  public void setTextAlign(@JdkConstants.HorizontalAlignment int align) {
    myTextAlign = align;
  }

  /**
   * Clear all special attributes of <code>SimpleColoredComponent</code>.
   * They are icon, text fragments and their attributes, "paint focus border".
   */
  public void clear() {
    _clear();
    revalidateAndRepaint();
  }

  private synchronized void _clear() {
    myPaintFocusBorder = false;
    myIcons.clear();
    myFragments.clear();
    myLayouts.clear();
    myAttributes.clear();
    myFragmentTags = null;
    myMainTextLastIndex = -1;
    myFragmentPadding.clear();
  }

  /**
   * Sets a new component icon
   *
   * @param icon icon
   */
  public final void addIcon(@NotNull final Icon icon) {
    assert (icon != null);
    assert (myFragments.size() == myAttributes.size());
    // Add an icon that should be displayed after any already inserted fragments.
    myIcons.add(new PositionedIcon(icon, myFragments.size()));
    revalidateAndRepaint();
  }

  /**
   * @return "leave" (internal) internal paddings of the component
   */
  @NotNull
  public Insets getIpad() {
    return myIpad;
  }

  /**
   * Sets specified internal paddings
   *
   * @param ipad insets
   */
  public void setIpad(@NotNull Insets ipad) {
    myIpad = ipad;

    revalidateAndRepaint();
  }

  /**
   * @return gap between icon and text
   */
  public int getIconTextGap() {
    return myIconTextGap;
  }

  /**
   * Sets a new gap between icon and text
   *
   * @param iconTextGap the gap between text and icon
   * @throws IllegalArgumentException if the <code>iconTextGap</code>
   *                                  has a negative value
   */
  public void setIconTextGap(final int iconTextGap) {
    if (iconTextGap < 0) {
      throw new IllegalArgumentException("wrong iconTextGap: " + iconTextGap);
    }
    myIconTextGap = iconTextGap;

    revalidateAndRepaint();
  }

  public Border getMyBorder() {
    return myBorder;
  }

  public void setMyBorder(@Nullable Border border) {
    myBorder = border;
  }

  /**
   * Sets whether focus border is painted or not
   *
   * @param paintFocusBorder <code>true</code> or <code>false</code>
   */
  protected final void setPaintFocusBorder(final boolean paintFocusBorder) {
    myPaintFocusBorder = paintFocusBorder;

    repaint();
  }

  /**
   * Sets whether focus border extends to icon or not. If so then
   * component also extends the selection.
   *
   * @param focusBorderAroundIcon <code>true</code> or <code>false</code>
   */
  protected final void setFocusBorderAroundIcon(final boolean focusBorderAroundIcon) {
    myFocusBorderAroundIcon = focusBorderAroundIcon;

    repaint();
  }

  public boolean isIconOpaque() {
    return myIconOpaque;
  }

  public void setIconOpaque(final boolean iconOpaque) {
    myIconOpaque = iconOpaque;

    repaint();
  }

  @Override
  @NotNull
  public Dimension getPreferredSize() {
    return computePreferredSize(false);
  }

  @Override
  @NotNull
  public Dimension getMinimumSize() {
    return computePreferredSize(false);
  }

  @Nullable
  public synchronized Object getFragmentTag(int index) {
    if (myFragmentTags != null && index < myFragmentTags.size()) {
      return myFragmentTags.get(index);
    }
    return null;
  }

  @NotNull
  public final synchronized Dimension computePreferredSize(final boolean mainTextOnly) {
    // Calculate width
    int width = myIpad.left;

    for (PositionedIcon icon : myIcons) {
      width += icon.icon.getIconWidth() + myIconTextGap;
    }

    final Insets borderInsets = myBorder != null ? myBorder.getBorderInsets(this) : JBUI.emptyInsets();
    width += borderInsets.left;

    final Font font = getBaseFont();

    width += (int)computeTextWidth(font, mainTextOnly);
    width += myIpad.right + borderInsets.right;

    // Take into account that the component itself can have a border
    final Insets insets = getInsets();
    if (insets != null) {
      width += insets.left + insets.right;
    }

    final int height = computePreferredHeight();

    return new Dimension(width, height);
  }

  public final synchronized int computePreferredHeight() {
    int height = myIpad.top + myIpad.bottom;

    final Font font = getBaseFont();

    final FontMetrics metrics = getFontMetrics(font);
    int textHeight = Math.max(JBUI.scale(16), metrics.getHeight()); //avoid too narrow rows

    final Insets borderInsets = myBorder != null ? myBorder.getBorderInsets(this) : JBUI.emptyInsets();
    textHeight += borderInsets.top + borderInsets.bottom;

    if (!myIcons.isEmpty()) {
      for (PositionedIcon icon : myIcons) {
        height += Math.max(icon.icon.getIconHeight(), textHeight);
      }
    }
    else {
      height += textHeight;
    }

    // Take into account that the component itself can have a border
    final Insets insets = getInsets();
    if (insets != null) {
      height += insets.top + insets.bottom;
    }

    return height;
  }

  private Rectangle computePaintArea() {
    final Rectangle area = new Rectangle(getWidth(), getHeight());
    JBInsets.removeFrom(area, getInsets());
    JBInsets.removeFrom(area, myIpad);
    return area;
  }

  private float computeTextWidth(@NotNull Font font, final boolean mainTextOnly) {
    float result = 0;
    final int baseSize = font.getSize();
    boolean wasSmaller = false;
    for (int i = 0; i < myAttributes.size(); i++) {
      final SimpleTextAttributes attributes = myAttributes.get(i);
      final boolean isSmaller = attributes.isSmaller();
      if (font.getStyle() != attributes.getFontStyle() || isSmaller != wasSmaller) { // derive font only if it is necessary
        font = font.deriveFont(attributes.getFontStyle(), isSmaller ? UIUtil.getFontSize(UIUtil.FontSize.SMALL) : baseSize);
      }
      wasSmaller = isSmaller;

      result += computeStringWidth(i, font);

      final int fixedWidth = (int)myFragmentPadding.get(i);
      if (fixedWidth > 0 && result < fixedWidth) {
        result = fixedWidth;
      }
      if (mainTextOnly && myMainTextLastIndex >= 0 && i == myMainTextLastIndex) break;
    }
    return result;
  }

  @NotNull
  private Font getBaseFont() {
    Font font = getFont();
    if (font == null) font = UIUtil.getLabelFont();
    return font;
  }

  private TextLayout getTextLayout(int fragmentIndex, Font font, FontRenderContext frc) {
    if (getBaseFont() != myLayoutFont) myLayouts.clear();
    TextLayout layout = fragmentIndex < myLayouts.size() ? myLayouts.get(fragmentIndex) : null;
    if (layout == null && needFontFallback(font, myFragments.get(fragmentIndex))) {
      layout = createAndCacheTextLayout(fragmentIndex, font, frc);
    }
    return layout;
  }

  private void doDrawString(Graphics2D g, int fragmentIndex, float x, float y) {
    final String text = myFragments.get(fragmentIndex);
    if (StringUtil.isEmpty(text)) return;
    final TextLayout layout = getTextLayout(fragmentIndex, g.getFont(), g.getFontRenderContext());
    if (layout != null) {
      layout.draw(g, x, y);
    }
    else {
      g.drawString(text, x, y);
    }
  }

  private float computeStringWidth(int fragmentIndex, Font font) {
    final String text = myFragments.get(fragmentIndex);
    if (StringUtil.isEmpty(text)) return 0;
    final FontRenderContext fontRenderContext = getFontMetrics(font).getFontRenderContext();
    final TextLayout layout = getTextLayout(fragmentIndex, font, fontRenderContext);
    if (layout != null) {
      return layout.getAdvance();
    }
    else {
      return (float)font.getStringBounds(text, fontRenderContext).getWidth();
    }
  }

  private TextLayout createAndCacheTextLayout(int fragmentIndex, Font basefont, FontRenderContext fontRenderContext) {
    final String text = myFragments.get(fragmentIndex);
    final AttributedString string = new AttributedString(text);
    final int start = 0;
    final int end = text.length();
    final AttributedCharacterIterator it = string.getIterator(new AttributedCharacterIterator.Attribute[0], start, end);
    Font currentFont = basefont;
    int currentIndex = start;
    for (char c = it.first(); c != CharacterIterator.DONE; c = it.next()) {
      // TODO(jacobr): SuitableFontProvider is a private class so we can't
      // easily use it. How important is supporting this use case?
      /*
      if (!font.canDisplay(c)) {
        for (SuitableFontProvider provider : SuitableFontProvider.EP_NAME.getExtensions()) {
          font = provider.getFontAbleToDisplay(c, basefont.getSize(), basefont.getStyle(), basefont.getFamily());
          if (font != null) break;
        }
      }
      */
      final int i = it.getIndex();
      if (!Comparing.equal(currentFont, basefont)) {
        if (i > currentIndex) {
          string.addAttribute(TextAttribute.FONT, currentFont, currentIndex, i);
        }
        currentFont = basefont;
        currentIndex = i;
      }
    }
    if (currentIndex < end) {
      string.addAttribute(TextAttribute.FONT, currentFont, currentIndex, end);
    }
    final TextLayout layout = new TextLayout(string.getIterator(), fontRenderContext);
    if (fragmentIndex >= myLayouts.size()) {
      myLayouts.addAll(Collections.nCopies(fragmentIndex - myLayouts.size() + 1, null));
    }
    myLayouts.set(fragmentIndex, layout);
    myLayoutFont = getBaseFont();
    return layout;
  }

  private static boolean needFontFallback(Font font, String text) {
    return font.canDisplayUpTo(text) != -1
           && text.indexOf(CharacterIterator.DONE) == -1; // see IDEA-137517, TextLayout does not support this character
  }

  /**
   * Returns the index of text fragment at the specified X offset.
   *
   * @param x the offset
   * @return the index of the fragment, {@link #FRAGMENT_ICON} if the icon is at the offset, or -1 if nothing is there.
   */
  public int findFragmentAt(int x) {
    float curX = myIpad.left;
    if (myBorder != null) {
      curX += myBorder.getBorderInsets(this).left;
    }

    curX += computeTextAlignShift();

    Font font = getBaseFont();

    final int baseSize = font.getSize();
    boolean wasSmaller = false;
    int i = 0;
    int iconIndex = 0;
    while (true) {
      // Go through all icons before attribute i.
      while (iconIndex < myIcons.size() && myIcons.get(iconIndex).index <= i) {
        final int iconWidth = myIcons.get(iconIndex).icon.getIconWidth() + myIconTextGap * 2;
        if (x >= curX && x < curX + iconWidth) {
          return FRAGMENT_ICON - iconIndex;
        }
        curX += iconWidth;
        iconIndex++;
      }

      if (i >= myAttributes.size()) {
        break;
      }
      final SimpleTextAttributes attributes = myAttributes.get(i);
      final boolean isSmaller = attributes.isSmaller();
      if (font.getStyle() != attributes.getFontStyle() || isSmaller != wasSmaller) { // derive font only if it is necessary
        font = font.deriveFont(attributes.getFontStyle(), isSmaller ? UIUtil.getFontSize(UIUtil.FontSize.SMALL) : baseSize);
      }
      wasSmaller = isSmaller;

      final float curWidth = computeStringWidth(i, font);
      if (x >= curX && x < curX + curWidth) {
        return i;
      }
      curX += curWidth;
      final int fragmentPadding = (int)myFragmentPadding.get(i);
      if (fragmentPadding > 0 && curX < fragmentPadding) {
        curX = fragmentPadding;
      }
      i++;
    }
    return -1;
  }

  @Nullable
  public Object getFragmentTagAt(int x) {
    final int index = findFragmentAt(x);
    return index < 0 ? null : getFragmentTag(index);
  }

  @Nullable
  public Icon getIconAt(int x) {
    final int index = findFragmentAt(x);
    return index <= FRAGMENT_ICON ? myIcons.get(FRAGMENT_ICON - index).icon : null;
  }

  @NotNull
  protected JLabel formatToLabel(@NotNull JLabel label) {
    // TODO(jacobr): interleave icons inline?
    label.setIcon(!myIcons.isEmpty() ? myIcons.get(0).icon : null);

    if (!myFragments.isEmpty()) {
      final StringBuilder text = new StringBuilder();
      text.append("<html><body style=\"white-space:nowrap\">");

      for (int i = 0; i < myFragments.size(); i++) {
        final String fragment = myFragments.get(i);
        final SimpleTextAttributes attributes = myAttributes.get(i);
        final Object tag = getFragmentTag(i);
        if (tag instanceof BrowserLauncherTag) {
          formatLink(text, fragment, attributes, ((BrowserLauncherTag)tag).myUrl);
        }
        else {
          formatText(text, fragment, attributes);
        }
      }

      text.append("</body></html>");
      label.setText(text.toString());
    }

    return label;
  }

  static void formatText(@NotNull StringBuilder builder, @NotNull String fragment, @NotNull SimpleTextAttributes attributes) {
    if (!fragment.isEmpty()) {
      builder.append("<span");
      formatStyle(builder, attributes);
      builder.append('>').append(convertFragment(fragment)).append("</span>");
    }
  }

  static void formatLink(@NotNull StringBuilder builder,
                         @NotNull String fragment,
                         @NotNull SimpleTextAttributes attributes,
                         @NotNull String url) {
    if (!fragment.isEmpty()) {
      builder.append("<a href=\"").append(StringUtil.replace(url, "\"", "%22")).append("\"");
      formatStyle(builder, attributes);
      builder.append('>').append(convertFragment(fragment)).append("</a>");
    }
  }

  private static String convertFragment(@NotNull String fragment) {
    return StringUtil.escapeXmlEntities(fragment).replaceAll("\\\\n", "<br>");
  }

  private static void formatStyle(final StringBuilder builder, final SimpleTextAttributes attributes) {
    final Color fgColor = attributes.getFgColor();
    final Color bgColor = attributes.getBgColor();
    final int style = attributes.getStyle();

    final int pos = builder.length();
    if (fgColor != null) {
      builder.append("color:#").append(Integer.toString(fgColor.getRGB() & 0xFFFFFF, 16)).append(';');
    }
    if (bgColor != null) {
      builder.append("background-color:#").append(Integer.toString(bgColor.getRGB() & 0xFFFFFF, 16)).append(';');
    }
    if ((style & SimpleTextAttributes.STYLE_BOLD) != 0) {
      builder.append("font-weight:bold;");
    }
    if ((style & SimpleTextAttributes.STYLE_ITALIC) != 0) {
      builder.append("font-style:italic;");
    }
    if ((style & SimpleTextAttributes.STYLE_UNDERLINE) != 0) {
      builder.append("text-decoration:underline;");
    }
    else if ((style & SimpleTextAttributes.STYLE_STRIKEOUT) != 0) {
      builder.append("text-decoration:line-through;");
    }
    if (builder.length() > pos) {
      builder.insert(pos, " style=\"");
      builder.append('"');
    }
  }

  @Override
  protected void paintComponent(final Graphics g) {
    try {
      _doPaint(g);
    }
    catch (RuntimeException e) {
      LOG.warn(logSwingPath(), e);
      throw e;
    }
  }

  private synchronized void _doPaint(final Graphics g) {
    checkCanPaint(g);
    doPaint((Graphics2D)g);
  }

  protected void doPaint(final Graphics2D g) {
    doPaintTextBackground(g, 0);
    doPaintTextAndIcons(g, myFocusBorderAroundIcon || myIcons.isEmpty());
  }

  private void doPaintTextBackground(Graphics2D g, int offset) {
    if (isOpaque() || shouldDrawBackground()) {
      paintBackground(g, offset, getWidth() - offset, getHeight());
    }
  }

  protected void paintBackground(Graphics2D g, int x, int width, int height) {
    g.setColor(getBackground());
    g.fillRect(x, 0, width, height);
  }

  protected void doPaintIcon(@NotNull Graphics2D g, @NotNull Icon icon, int offset) {
    final Container parent = getParent();
    Color iconBackgroundColor = null;
    if ((isOpaque() || isIconOpaque()) && !isTransparentIconBackground()) {
      if (parent != null && !myFocusBorderAroundIcon && !UIUtil.isFullRowSelectionLAF()) {
        iconBackgroundColor = parent.getBackground();
      }
      else {
        iconBackgroundColor = getBackground();
      }
    }

    if (iconBackgroundColor != null) {
      g.setColor(iconBackgroundColor);
      g.fillRect(offset, 0, icon.getIconWidth() + myIpad.left + myIconTextGap, getHeight());
    }

    paintIcon(g, icon, offset + myIpad.left);
  }

  protected int doPaintTextAndIcons(Graphics2D g, boolean focusAroundIcon) {
    float offset = myIpad.left;
    if (myBorder != null) {
      offset += myBorder.getBorderInsets(this).left;
    }

    final List<Object[]> searchMatches = new ArrayList<>();

    applyAdditionalHints(g);
    final Font baseFont = getBaseFont();
    g.setFont(baseFont);
    offset += computeTextAlignShift();
    final int baseSize = baseFont.getSize();
    final FontMetrics baseMetrics = g.getFontMetrics();
    final Rectangle area = computePaintArea();
    final int textBaseline = area.y + getTextBaseLine(baseMetrics, area.height);
    boolean wasSmaller = false;
    assert (myFragments.size() == myAttributes.size());
    int i = 0;
    int iconIndex = 0;
    while (true) {
      // Go through all icons up to attribute i.
      while (iconIndex < myIcons.size() && myIcons.get(iconIndex).index <= i) {
        final Icon icon = myIcons.get(iconIndex).icon;
        final int iconWidth = icon.getIconWidth() + myIconTextGap;
        doPaintIcon(g, icon, (int)offset);
        offset += iconWidth + myIconTextGap;
        iconIndex++;
      }

      if (i >= myFragments.size()) {
        break;
      }

      final SimpleTextAttributes attributes = myAttributes.get(i);

      Font font = g.getFont();
      final boolean isSmaller = attributes.isSmaller();
      if (font.getStyle() != attributes.getFontStyle() || isSmaller != wasSmaller) { // derive font only if it is necessary
        font = font.deriveFont(attributes.getFontStyle(), isSmaller ? UIUtil.getFontSize(UIUtil.FontSize.SMALL) : baseSize);
      }
      wasSmaller = isSmaller;

      g.setFont(font);
      final FontMetrics metrics = g.getFontMetrics(font);

      final float fragmentWidth = computeStringWidth(i, font);

      final int fragmentPadding = (int)myFragmentPadding.get(i);

      final Color bgColor = attributes.isSearchMatch() ? null : attributes.getBgColor();
      if ((attributes.isOpaque() || isOpaque()) && bgColor != null) {
        g.setColor(bgColor);
        g.fillRect((int)offset, 0, (int)fragmentWidth, getHeight());
      }

      Color color = attributes.getFgColor();
      if (color == null) { // in case if color is not defined we have to get foreground color from Swing hierarchy
        color = getForeground();
      }
      if (!isEnabled()) {
        color = UIUtil.getInactiveTextColor();
      }
      g.setColor(color);

      final int fragmentAlignment = (int)myFragmentAlignment.get(i);

      final float endOffset;
      if (fragmentPadding > 0 &&
          fragmentPadding > fragmentWidth) {
        endOffset = fragmentPadding;
        if (fragmentAlignment == SwingConstants.RIGHT || fragmentAlignment == SwingConstants.TRAILING) {
          offset = fragmentPadding - fragmentWidth;
        }
      }
      else {
        endOffset = offset + fragmentWidth;
      }

      if (!attributes.isSearchMatch()) {
        if (shouldDrawMacShadow()) {
          g.setColor(SHADOW_COLOR);
          doDrawString(g, i, offset, textBaseline + 1);
        }

        if (shouldDrawDimmed()) {
          color = ColorUtil.dimmer(color);
        }

        g.setColor(color);
        doDrawString(g, i, offset, textBaseline);
      }

      // for some reason strokeState here may be incorrect, resetting the stroke helps
      g.setStroke(g.getStroke());

      // 1. Strikeout effect
      if (attributes.isStrikeout() && !attributes.isSearchMatch()) {
        EffectPainter.STRIKE_THROUGH.paint(g, (int)offset, textBaseline, (int)fragmentWidth, getCharHeight(g), font);
      }
      // 2. Waved effect
      if (attributes.isWaved()) {
        if (attributes.getWaveColor() != null) {
          g.setColor(attributes.getWaveColor());
        }
        EffectPainter.WAVE_UNDERSCORE.paint(g, (int)offset, textBaseline + 1, (int)fragmentWidth, Math.max(2, metrics.getDescent()), font);
      }
      // 3. Underline
      if (attributes.isUnderline()) {
        EffectPainter.LINE_UNDERSCORE.paint(g, (int)offset, textBaseline, (int)fragmentWidth, metrics.getDescent(), font);
      }
      // 4. Bold Dotted Line
      if (attributes.isBoldDottedLine()) {
        final int dottedAt = SystemInfo.isMac ? textBaseline : textBaseline + 1;
        final Color lineColor = attributes.getWaveColor();
        UIUtil.drawBoldDottedLine(g, (int)offset, (int)(offset + fragmentWidth), dottedAt, bgColor, lineColor, isOpaque());
      }

      if (attributes.isSearchMatch()) {
        searchMatches.add(new Object[]{offset, offset + fragmentWidth, (float)textBaseline, myFragments.get(i), g.getFont(), attributes});
      }

      offset = endOffset;
      i++;
    }

    // Paint focus border around the text and icon (if necessary)
    if (myPaintFocusBorder && myBorder != null) {
      if (focusAroundIcon) {
        myBorder.paintBorder(this, g, 0, 0, getWidth(), getHeight());
      }
      else {
        int textStart = 0;
        // Skip all icons that occur before any text.
        for (PositionedIcon positionedIcon : myIcons) {
          if (positionedIcon.index != 0) {
            break;
          }
          textStart += positionedIcon.icon.getIconWidth() + myIconTextGap;
        }

        myBorder.paintBorder(this, g, textStart, 0, getWidth() - textStart, getHeight());
      }
    }

    // draw search matches after all
    for (final Object[] info : searchMatches) {
      final float x1 = (float)info[0];
      final float x2 = (float)info[1];
      UIUtil.drawSearchMatch(g, x1, x2, getHeight());
      g.setFont((Font)info[4]);

      final float baseline = (float)info[2];
      final String text = (String)info[3];
      if (shouldDrawMacShadow()) {
        g.setColor(SHADOW_COLOR);
        g.drawString(text, x1, baseline + 1);
      }

      g.setColor(new JBColor(Gray._50, Gray._0));
      g.drawString(text, x1, baseline);

      if (((SimpleTextAttributes)info[5]).isStrikeout()) {
        EffectPainter.STRIKE_THROUGH.paint(g, (int)x1, (int)baseline, (int)(x2 - x1), getCharHeight(g), g.getFont());
      }
    }
    return (int)offset;
  }

  private static int getCharHeight(Graphics g) {
    // magic of determining character height
    return g.getFontMetrics().charWidth('a');
  }

  private int computeTextAlignShift() {
    if (myTextAlign == SwingConstants.LEFT || myTextAlign == SwingConstants.LEADING) {
      return 0;
    }

    final int componentWidth = getSize().width;
    final int excessiveWidth = componentWidth - computePreferredSize(false).width;
    if (excessiveWidth <= 0) {
      return 0;
    }

    if (myTextAlign == SwingConstants.CENTER) {
      return excessiveWidth / 2;
    }
    else if (myTextAlign == SwingConstants.RIGHT || myTextAlign == SwingConstants.TRAILING) {
      return excessiveWidth;
    }
    return 0;
  }

  protected boolean shouldDrawMacShadow() {
    return false;
  }

  protected boolean shouldDrawDimmed() {
    return false;
  }

  protected boolean shouldDrawBackground() {
    return false;
  }

  protected void paintIcon(@NotNull Graphics g, @NotNull Icon icon, int offset) {
    final Rectangle area = computePaintArea();
    icon.paintIcon(this, g, offset, area.y + (area.height - icon.getIconHeight() + 1) / 2);
  }

  protected void applyAdditionalHints(@NotNull Graphics2D g) {
    UISettings.setupAntialiasing(g);
  }

  @Override
  public int getBaseline(int width, int height) {
    super.getBaseline(width, height);
    return getTextBaseLine(getFontMetrics(getFont()), height);
  }

  public boolean isTransparentIconBackground() {
    return myTransparentIconBackground;
  }

  public void setTransparentIconBackground(boolean transparentIconBackground) {
    myTransparentIconBackground = transparentIconBackground;
  }

  public static int getTextBaseLine(@NotNull FontMetrics metrics, final int height) {
    // adding leading to ascent, just like in editor (leads to bad presentation for certain fonts with Oracle JDK, see IDEA-167541)
    return (height - metrics.getHeight()) / 2 + metrics.getAscent() +
           (SystemInfo.isJetBrainsJvm ? metrics.getLeading() : 0);
  }

  private static void checkCanPaint(Graphics g) {
    if (UIUtil.isPrinting(g)) return;

    /* wtf??
    if (!isDisplayable()) {
      LOG.assertTrue(false, logSwingPath());
    }
    */
    final Application application = ApplicationManager.getApplication();
    if (application != null) {
      application.assertIsDispatchThread();
    }
    else if (!SwingUtilities.isEventDispatchThread()) {
      throw new RuntimeException(Thread.currentThread().toString());
    }
  }

  @NotNull
  private String logSwingPath() {
    final StringBuilder buffer = new StringBuilder("Components hierarchy:\n");
    for (Container c = this; c != null; c = c.getParent()) {
      buffer.append('\n');
      buffer.append(c);
    }
    return buffer.toString();
  }

  protected void setBorderInsets(Insets insets) {
    if (myBorder instanceof MyBorder) {
      ((MyBorder)myBorder).setInsets(insets);
    }

    revalidateAndRepaint();
  }

  private static final class MyBorder implements Border {
    private Insets myInsets;

    public MyBorder() {
      myInsets = JBUI.insets(1);
    }

    public void setInsets(final Insets insets) {
      myInsets = insets;
    }

    @Override
    public void paintBorder(final Component c, final Graphics g, final int x, final int y, final int width, final int height) {
    }

    @Override
    public Insets getBorderInsets(final Component c) {
      return (Insets)myInsets.clone();
    }

    @Override
    public boolean isBorderOpaque() {
      return false;
    }
  }

  @NotNull
  public CharSequence getCharSequence(boolean mainOnly) {
    final List<String> fragments = mainOnly && myMainTextLastIndex > -1 && myMainTextLastIndex + 1 < myFragments.size() ?
                                   myFragments.subList(0, myMainTextLastIndex + 1) : myFragments;
    return StringUtil.join(fragments, "");
  }

  @Override
  public String toString() {
    return getCharSequence(false).toString();
  }

  public void change(@NotNull Runnable runnable, boolean autoInvalidate) {
    final boolean old = myAutoInvalidate;
    myAutoInvalidate = autoInvalidate;
    try {
      runnable.run();
    }
    finally {
      myAutoInvalidate = old;
    }
  }

  @Override
  public AccessibleContext getAccessibleContext() {
    if (accessibleContext == null) {
      accessibleContext = new AccessibleSimpleColoredComponent();
    }
    return accessibleContext;
  }

  protected class AccessibleSimpleColoredComponent extends JComponent.AccessibleJComponent {
    @Override
    public String getAccessibleName() {
      return getCharSequence(false).toString();
    }

    @Override
    public AccessibleRole getAccessibleRole() {
      return AccessibleRole.LABEL;
    }
  }

  public static class BrowserLauncherTag implements Runnable {
    private final String myUrl;

    public BrowserLauncherTag(@NotNull String url) {
      myUrl = url;
    }

    @Override
    public void run() {
      BrowserUtil.browse(myUrl);
    }
  }

  public interface ColoredIterator extends Iterator<String> {
    int getOffset();

    int getEndOffset();

    @NotNull
    String getFragment();

    @NotNull
    SimpleTextAttributes getTextAttributes();

    int split(int offset, @NotNull SimpleTextAttributes attributes);
  }

  private class MyIterator implements ColoredIterator {
    int myIndex = -1;
    int myOffset;
    int myEndOffset;

    @Override
    public int getOffset() {
      return myOffset;
    }

    @Override
    public int getEndOffset() {
      return myEndOffset;
    }

    @NotNull
    @Override
    public String getFragment() {
      return myFragments.get(myIndex);
    }

    @NotNull
    @Override
    public SimpleTextAttributes getTextAttributes() {
      return myAttributes.get(myIndex);
    }

    @Override
    public int split(int offset, @NotNull SimpleTextAttributes attributes) {
      if (offset < 0 || offset > myEndOffset - myOffset) {
        throw new IllegalArgumentException(offset + " is not within [0, " + (myEndOffset - myOffset) + "]");
      }
      if (offset == myEndOffset - myOffset) {   // replace
        myAttributes.set(myIndex, attributes);
      }
      else if (offset > 0) {   // split
        final String text = getFragment();
        myFragments.set(myIndex, text.substring(0, offset));
        myAttributes.add(myIndex, attributes);
        myFragments.add(myIndex + 1, text.substring(offset));
        if (myFragmentTags != null && myFragmentTags.size() > myIndex) {
          myFragmentTags.add(myIndex, myFragments.get(myIndex));
        }
        if (myIndex < myLayouts.size()) myLayouts.set(myIndex, null);
        if ((myIndex + 1) < myLayouts.size()) myLayouts.add(myIndex + 1, null);
        myIndex++;
      }
      myOffset += offset;
      return myOffset;
    }

    @Override
    public boolean hasNext() {
      return myIndex + 1 < myFragments.size();
    }

    @Override
    public String next() {
      myIndex++;
      myOffset = myEndOffset;
      final String text = getFragment();
      myEndOffset += text.length();
      return text;
    }

    @Override
    public void remove() {
      throw new UnsupportedOperationException();
    }
  }
}
