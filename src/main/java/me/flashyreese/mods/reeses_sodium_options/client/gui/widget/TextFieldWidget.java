package me.flashyreese.mods.reeses_sodium_options.client.gui.widget;

import com.dhj.actinium.gui.rso.compat.CharacterEvent;
import com.dhj.actinium.gui.rso.compat.Component;
import com.dhj.actinium.gui.rso.compat.ComponentPath;
import com.dhj.actinium.gui.rso.compat.FocusNavigationEvent;
import com.dhj.actinium.gui.rso.compat.Font;
import com.dhj.actinium.gui.rso.compat.FormattedCharSequence;
import com.dhj.actinium.gui.rso.compat.GuiGraphicsExtractor;
import com.dhj.actinium.gui.rso.compat.KeyEvent;
import com.dhj.actinium.gui.rso.compat.MouseButtonEvent;
import com.dhj.actinium.gui.rso.compat.NarratedElementType;
import com.dhj.actinium.gui.rso.compat.NarrationElementOutput;
import com.dhj.actinium.gui.rso.compat.Style;
import me.flashyreese.mods.reeses_sodium_options.client.config.ReeseSodiumOptionsConfig;
import me.flashyreese.mods.reeses_sodium_options.client.gui.control.ControlGuide;
import me.flashyreese.mods.reeses_sodium_options.client.gui.control.ControlGuideProvider;
import me.flashyreese.mods.reeses_sodium_options.client.gui.layout.LayoutBounds;
import me.flashyreese.mods.reeses_sodium_options.client.gui.theme.GuiThemes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.math.MathHelper;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NonNull;

import java.util.List;

public class TextFieldWidget extends BaseWidget implements ControlGuideProvider {
    private static final long CURSOR_ANIMATION_DURATION = 750;

    private final Font font = new Font(Minecraft.getMinecraft().fontRenderer);
    private final @Nullable Component placeholder;

    protected boolean selecting;
    protected String text = "";
    protected int maxLength = 100;
    protected boolean visible = true;
    protected boolean editable = true;
    private int firstCharacterIndex;
    private int selectionStart;
    private int selectionEnd;
    private int lastCursorPosition;
    private long nextCursorUpdate;
    private boolean currentCursorState;
    private float currentCursorAlpha;

    public TextFieldWidget(LayoutBounds dim, @Nullable Component placeholder) {
        super(dim);
        this.placeholder = placeholder;
    }

    @Override
    public List<ControlGuide> controlGuides() {
        return this.isVisible() && this.isEditable() && this.isFocused()
                ? List.of(ControlGuide.press(Component.translatable("rso.controller.guide.edit")))
                : List.of();
    }

    /** Called whenever the text content changes. */
    protected void onTextChanged(String text) {
    }

    /** Called when Enter is pressed; the event carries modifiers such as Shift. */
    protected boolean onSubmit(KeyEvent event) {
        return false;
    }

    /** Called on any direct user interaction (key press or mouse click). */
    protected void onInteraction() {
    }

    @Override
    public void extractRenderState(@NotNull GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float delta) {
        if (!this.isVisible()) {
            return;
        }
        updateCursorAlpha();
        if (!this.isFocused() && this.text.isBlank() && this.placeholder != null) {
            this.drawString(guiGraphics, this.placeholder, this.getX() + 6, this.getY() + 6, GuiThemes.DEFAULT_BUTTON.themeDarker);
        }

        this.drawRect(guiGraphics, this.getX(), this.getY(), this.getLimitX(), this.getLimitY(), this.isFocused() ? GuiThemes.DEFAULT_BUTTON.bgHighlight : GuiThemes.DEFAULT_BUTTON.bgDefault);
        int selectionStartOffset = this.selectionStart - this.firstCharacterIndex;
        int selectionEndOffset = this.selectionEnd - this.firstCharacterIndex;
        String displayedText = this.font.getDelegate().trimStringToWidth(this.text.substring(this.firstCharacterIndex), this.getInnerWidth());
        boolean isCursorWithinDisplayedText = selectionStartOffset >= 0 && selectionStartOffset <= displayedText.length();
        int textStartX = this.getX() + 6;
        int textStartY = this.getY() + 6;
        int textEndX = textStartX;
        if (selectionEndOffset > displayedText.length()) {
            selectionEndOffset = displayedText.length();
        }
        if (!displayedText.isEmpty()) {
            String preCursorText = isCursorWithinDisplayedText ? displayedText.substring(0, selectionStartOffset) : displayedText;
            guiGraphics.text(this.font, formatted(preCursorText), textEndX, textStartY, 0xFFFFFFFF);
            textEndX = textEndX + this.font.width(formatted(preCursorText));
        }
        boolean isCursorAtEnd = this.selectionStart < this.text.length() || this.text.length() >= this.maxLength;
        int cursorX = textEndX;
        if (!isCursorWithinDisplayedText) {
            cursorX = selectionStartOffset > 0 ? textStartX + this.getWidth() - 12 : textStartX;
        } else if (isCursorAtEnd) {
            --cursorX;
            --textEndX;
        }
        if (!displayedText.isEmpty() && isCursorWithinDisplayedText && selectionStartOffset < displayedText.length()) {
            guiGraphics.text(this.font, formatted(displayedText.substring(selectionStartOffset)), textEndX, textStartY, 0xFFFFFFFF);
        }
        if (this.isFocused()) {
            int color = ((int) (this.currentCursorAlpha * 255) << 24) | 0x00D0D0D0;
            guiGraphics.fill(cursorX, textStartY - 1, cursorX + 1, textStartY + 1 + this.font.lineHeight, color);
        }
        if (selectionEndOffset != selectionStartOffset) {
            int selectionEndX = textStartX + this.font.width(displayedText.substring(0, selectionEndOffset));
            this.drawSelectionHighlight(guiGraphics, cursorX, textStartY - 1, selectionEndX - 1, textStartY + 1 + this.font.lineHeight);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean repeated) {
        int clickX = MathHelper.floor(event.x()) - this.getX() - 6;
        String displayedText = this.font.getDelegate().trimStringToWidth(this.text.substring(this.firstCharacterIndex), this.getInnerWidth());
        this.setCursor(this.font.getDelegate().trimStringToWidth(displayedText, clickX).length() + this.firstCharacterIndex);

        this.setFocused(this.isMouseOver(event.x(), event.y()));
        this.onInteraction();
        return this.isFocused();
    }

    private void drawSelectionHighlight(GuiGraphicsExtractor guiGraphics, int startX, int startY, int endX, int endY) {
        int temp;
        if (startX < endX) {
            temp = startX;
            startX = endX;
            endX = temp;
        }
        if (startY < endY) {
            temp = startY;
            startY = endY;
            endY = temp;
        }
        if (endX > this.getX() + this.getWidth()) {
            endX = this.getX() + this.getWidth();
        }
        if (startX > this.getX() + this.getWidth()) {
            startX = this.getX() + this.getWidth();
        }
        guiGraphics.fill(startX, startY, endX, endY, GuiThemes.SELECTED_UNDERLINE);
    }

    private static FormattedCharSequence formatted(String text) {
        return FormattedCharSequence.forward(text, Style.EMPTY);
    }

    public String getSelectedText() {
        int selectionStartIndex = Math.min(this.selectionStart, this.selectionEnd);
        int selectionEndIndex = Math.max(this.selectionStart, this.selectionEnd);
        return this.text.substring(selectionStartIndex, selectionEndIndex);
    }

    public String getText() {
        return this.text;
    }

    public boolean rso$acceptChar(char ch, int modifiers) {
        if (!this.isVisible() || !this.isEditable()) {
            return false;
        }

        this.setFocused(true);
        this.write(String.valueOf(ch));
        return true;
    }

    public boolean rso$acceptKeyCode(int keycode, int scancode, int modifiers) {
        if (!this.isVisible()) {
            return false;
        }

        this.setFocused(true);
        return this.keyPressed(new KeyEvent(keycode, scancode, modifiers));
    }

    public boolean rso$moveCursor(int amount) {
        if (!this.isVisible()) {
            return false;
        }

        this.moveCursor(amount);
        return true;
    }

    public boolean rso$copyText() {
        if (!this.isVisible()) {
            return false;
        }

        String selectedText = this.getSelectedText();
        GuiScreen.setClipboardString(selectedText.isEmpty() ? this.text : selectedText);
        return true;
    }

    public void write(String text) {
        int selectionStartIndex = Math.min(this.selectionStart, this.selectionEnd);
        int selectionEndIndex = Math.max(this.selectionStart, this.selectionEnd);
        int availableSpace = this.maxLength - this.text.length() - (selectionStartIndex - selectionEndIndex);
        String filteredText = filterText(text);
        int filteredTextLength = filteredText.length();
        if (availableSpace < filteredTextLength) {
            filteredText = filteredText.substring(0, availableSpace);
            filteredTextLength = availableSpace;
        }

        this.currentCursorState = true;
        this.nextCursorUpdate = System.currentTimeMillis() + CURSOR_ANIMATION_DURATION;

        this.text = new StringBuilder(this.text).replace(selectionStartIndex, selectionEndIndex, filteredText).toString();
        this.setSelectionStart(selectionStartIndex + filteredTextLength);
        this.setSelectionEnd(this.selectionStart);
        this.onTextChanged(this.text);
    }

    private static String filterText(String text) {
        StringBuilder builder = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch != 167 && ch >= 32 && ch != 127) {
                builder.append(ch);
            }
        }
        return builder.toString();
    }

    public boolean hasText() {
        return !this.text.isEmpty();
    }

    public void clearText() {
        if (this.text.isEmpty()) {
            return;
        }

        this.text = "";
        this.firstCharacterIndex = 0;
        this.selectionStart = 0;
        this.selectionEnd = 0;
        this.lastCursorPosition = 0;
        this.onTextChanged(this.text);
    }

    public void selectAllText() {
        this.setCursorToEnd();
        this.setSelectionEnd(0);
    }

    private void erase(int offset) {
        if (GuiScreen.isCtrlKeyDown()) {
            this.eraseWords(offset);
        } else {
            this.eraseCharacters(offset);
        }

    }

    public void eraseWords(int wordOffset) {
        if (!this.text.isEmpty()) {
            if (this.selectionEnd != this.selectionStart) {
                this.write("");
            } else {
                this.eraseCharacters(this.getWordSkipPosition(wordOffset) - this.selectionStart);
            }
        }
    }

    public void eraseCharacters(int characterOffset) {
        if (!this.text.isEmpty()) {
            if (this.selectionEnd != this.selectionStart) {
                this.write("");
            } else {
                int cursorPosWithOffset = this.getCursorPosWithOffset(characterOffset);
                int startIndex = Math.min(cursorPosWithOffset, this.selectionStart);
                int endIndex = Math.max(cursorPosWithOffset, this.selectionStart);
                if (startIndex != endIndex) {
                    this.text = new StringBuilder(this.text).delete(startIndex, endIndex).toString();
                    this.setCursor(startIndex);
                    this.onTextChanged(this.text);
                }
            }
        }
    }

    public int getWordSkipPosition(int wordOffset) {
        return this.getWordSkipPosition(wordOffset, this.getCursor());
    }

    private int getWordSkipPosition(int wordOffset, int cursorPosition) {
        return this.getWordSkipPosition(wordOffset, cursorPosition, true);
    }

    private int getWordSkipPosition(int wordOffset, int cursorPosition, boolean skipOverSpaces) {
        int newPosition = cursorPosition;
        boolean isNegativeOffset = wordOffset < 0;
        int absoluteOffset = Math.abs(wordOffset);

        for (int i = 0; i < absoluteOffset; ++i) {
            if (!isNegativeOffset) {
                int textLength = this.text.length();
                newPosition = this.text.indexOf(' ', newPosition);
                if (newPosition == -1) {
                    newPosition = textLength;
                } else {
                    while (skipOverSpaces && newPosition < textLength && this.text.charAt(newPosition) == ' ') {
                        ++newPosition;
                    }
                }
            } else {
                while (skipOverSpaces && newPosition > 0 && this.text.charAt(newPosition - 1) == ' ') {
                    --newPosition;
                }
                while (newPosition > 0 && this.text.charAt(newPosition - 1) != ' ') {
                    --newPosition;
                }
            }
        }

        return newPosition;
    }

    public int getCursor() {
        return this.selectionStart;
    }

    public void setCursor(int cursor) {
        this.setSelectionStart(cursor);
        if (!this.selecting) {
            this.setSelectionEnd(this.selectionStart);
        }
    }

    public void moveCursor(int offset) {
        this.setCursor(this.getCursorPosWithOffset(offset));
    }

    private int getCursorPosWithOffset(int offset) {
        return offsetByCodepoints(this.text, this.selectionStart, offset);
    }

    private static int offsetByCodepoints(String text, int start, int amount) {
        int index = start;
        int direction = amount < 0 ? -1 : 1;
        int remaining = Math.abs(amount);
        while (remaining > 0) {
            if (direction > 0) {
                if (index >= text.length()) {
                    break;
                }
                index += Character.charCount(text.codePointAt(index));
            } else {
                if (index <= 0) {
                    break;
                }
                index -= Character.charCount(text.codePointBefore(index));
            }
            remaining--;
        }
        return MathHelper.clamp(index, 0, text.length());
    }

    public void setSelectionStart(int cursor) {
        this.selectionStart = MathHelper.clamp(cursor, 0, this.text.length());
    }

    public void setCursorToStart() {
        this.setCursor(0);
    }

    public void setCursorToEnd() {
        this.setCursor(this.text.length());
    }

    public void setSelectionEnd(int index) {
        int textLength = this.text.length();
        this.selectionEnd = MathHelper.clamp(index, 0, textLength);
        if (this.firstCharacterIndex > textLength) {
            this.firstCharacterIndex = textLength;
        }

        int innerWidth = this.getInnerWidth();
        String displayText = this.font.getDelegate().trimStringToWidth(this.text.substring(this.firstCharacterIndex), innerWidth);
        int endIndex = displayText.length() + this.firstCharacterIndex;
        if (this.selectionEnd == this.firstCharacterIndex) {
            this.firstCharacterIndex -= this.font.getDelegate().trimStringToWidth(this.text, innerWidth, true).length();
        }

        if (this.selectionEnd > endIndex) {
            this.firstCharacterIndex += this.selectionEnd - endIndex;
        } else if (this.selectionEnd <= this.firstCharacterIndex) {
            this.firstCharacterIndex -= this.firstCharacterIndex - this.selectionEnd;
        }

        this.firstCharacterIndex = MathHelper.clamp(this.firstCharacterIndex, 0, textLength);
    }

    public boolean isActive() {
        return this.isVisible();
    }

    private boolean canConsumeTextInput() {
        return this.isVisible() && this.isFocused() && this.isEditable();
    }

    @Override
    public boolean charTyped(@NonNull CharacterEvent characterEvent) {
        if (!this.canConsumeTextInput()) {
            return false;
        }
        if (characterEvent.isAllowedChatCharacter()) {
            if (this.editable) {
                this.write(characterEvent.codepointAsString());
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean keyPressed(@NonNull KeyEvent event) {
        this.onInteraction();
        if (!this.canConsumeTextInput()) {
            return false;
        } else {
            this.selecting = event.hasShiftDown();
            if (event.isSelectAll()) {
                this.setCursorToEnd();
                this.setSelectionEnd(0);
                return true;
            } else if (event.isCopy()) {
                GuiScreen.setClipboardString(this.getSelectedText());
                return true;
            } else if (event.isPaste()) {
                if (this.editable) {
                    this.write(GuiScreen.getClipboardString());
                }

                return true;
            } else if (event.isCut()) {
                GuiScreen.setClipboardString(this.getSelectedText());
                if (this.editable) {
                    this.write("");
                }

                return true;
            } else {
                switch (event.key()) {
                    case com.dhj.actinium.gui.rso.compat.KeyEvent.KEY_ENTER,
                            com.dhj.actinium.gui.rso.compat.KeyEvent.KEY_KP_ENTER -> {
                        return this.onSubmit(event);
                    }
                    case com.dhj.actinium.gui.rso.compat.KeyEvent.KEY_BACKSPACE -> {
                        if (this.editable) {
                            this.selecting = false;
                            this.erase(-1);
                            this.selecting = event.hasShiftDown();
                        }
                        return true;
                    }
                    case com.dhj.actinium.gui.rso.compat.KeyEvent.KEY_DELETE -> {
                        if (this.editable) {
                            this.selecting = false;
                            this.erase(1);
                            this.selecting = event.hasShiftDown();
                        }
                        return true;
                    }
                    case com.dhj.actinium.gui.rso.compat.KeyEvent.KEY_RIGHT -> {
                        if (event.hasControlDown()) {
                            this.setCursor(this.getWordSkipPosition(1));
                        } else {
                            this.moveCursor(1);
                        }
                        boolean state = this.getCursor() != this.lastCursorPosition && this.getCursor() != this.text.length() + 1;
                        this.lastCursorPosition = this.getCursor();
                        return state;
                    }
                    case com.dhj.actinium.gui.rso.compat.KeyEvent.KEY_LEFT -> {
                        if (event.hasControlDown()) {
                            this.setCursor(this.getWordSkipPosition(-1));
                        } else {
                            this.moveCursor(-1);
                        }
                        boolean state = this.getCursor() != this.lastCursorPosition && this.getCursor() != 0;
                        this.lastCursorPosition = this.getCursor();
                        return state;
                    }
                    case com.dhj.actinium.gui.rso.compat.KeyEvent.KEY_HOME -> {
                        this.setCursorToStart();
                        return true;
                    }
                    case com.dhj.actinium.gui.rso.compat.KeyEvent.KEY_END -> {
                        this.setCursorToEnd();
                        return true;
                    }
                    default -> {
                        return false;
                    }
                }
            }
        }
    }

    private void updateCursorAlpha() {
        if (ReeseSodiumOptionsConfig.config().isReducedMotion()) {
            this.currentCursorAlpha = 1f;
            return;
        }

        long currentTimeMillis = System.currentTimeMillis();
        if (currentTimeMillis >= this.nextCursorUpdate) {
            this.currentCursorState = !this.currentCursorState;
            this.nextCursorUpdate = currentTimeMillis + CURSOR_ANIMATION_DURATION;
        }

        float cursorAlpha = (float) (this.nextCursorUpdate - currentTimeMillis) / CURSOR_ANIMATION_DURATION;

        if (cursorAlpha <= 0.25f) {
            cursorAlpha *= 4f;
        } else if (cursorAlpha >= 0.75f) {
            cursorAlpha = (1 - cursorAlpha) * 4f;
        } else {
            cursorAlpha = 1f;
        }

        cursorAlpha = Math.max(0f, Math.min(1f, cursorAlpha));

        this.currentCursorAlpha = this.currentCursorState ? 1 : 1 - cursorAlpha;
    }

    public boolean isVisible() {
        return this.visible;
    }

    public boolean isEditable() {
        return this.editable;
    }

    public int getInnerWidth() {
        return this.getWidth() - 12;
    }

    @Override
    public @Nullable ComponentPath nextFocusPath(@NonNull FocusNavigationEvent navigation) {
        if (!this.visible) {
            return null;
        }
        return super.nextFocusPath(navigation);
    }

    @Override
    public void updateNarration(@NonNull NarrationElementOutput builder) {
        Component label = this.placeholder == null ? Component.empty() : this.placeholder;
        builder.add(NarratedElementType.TITLE, Component.translatable("gui.narrate.editBox", label, this.text));
    }
}
