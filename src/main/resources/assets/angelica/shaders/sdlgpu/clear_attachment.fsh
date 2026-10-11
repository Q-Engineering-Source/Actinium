#version 460 core
// The body must contain a statement: the pinned glsl-transformation-lib (0.2.0-32) NPEs when
// AttachmentClear appends the color-clear writes to an empty main().
void main() { float angelica_clearNoop = 0.0; }
