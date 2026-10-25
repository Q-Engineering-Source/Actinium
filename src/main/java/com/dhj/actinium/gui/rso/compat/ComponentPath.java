package com.dhj.actinium.gui.rso.compat;

/**
 * Newer-Minecraft style focus path adapted to the 1.12.2 client.
 * Represents the chain of containers leading to a focused leaf widget.
 */
public final class ComponentPath {
    private final GuiEventListener component;
    private final ComponentPath child;

    private ComponentPath(GuiEventListener component, ComponentPath child) {
        this.component = component;
        this.child = child;
    }

    public static ComponentPath leaf(GuiEventListener component) {
        return new ComponentPath(component, null);
    }

    public static ComponentPath path(ContainerEventHandler container, ComponentPath childPath) {
        return new ComponentPath(container, childPath);
    }

    public GuiEventListener leafComponent() {
        return this.child == null ? this.component : this.child.leafComponent();
    }

    /** Returns the child path, or null when this path is a leaf. */
    public ComponentPath child() {
        return this.child;
    }
}
