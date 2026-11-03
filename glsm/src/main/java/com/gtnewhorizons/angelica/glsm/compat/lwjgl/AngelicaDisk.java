package com.gtnewhorizons.angelica.glsm.compat.lwjgl;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import org.lwjgl.util.glu.Disk;

import static org.lwjgl.opengl.GL11.GL_LINE_LOOP;
import static org.lwjgl.opengl.GL11.GL_LINE_STRIP;
import static org.lwjgl.opengl.GL11.GL_POINTS;
import static org.lwjgl.opengl.GL11.GL_TRIANGLE_STRIP;
import static org.lwjgl.util.glu.GLU.GLU_FILL;
import static org.lwjgl.util.glu.GLU.GLU_LINE;
import static org.lwjgl.util.glu.GLU.GLU_NONE;
import static org.lwjgl.util.glu.GLU.GLU_OUTSIDE;
import static org.lwjgl.util.glu.GLU.GLU_POINT;
import static org.lwjgl.util.glu.GLU.GLU_SILHOUETTE;
import com.gtnewhorizons.angelica.glsm.GLSMCurrentAttribs;
import com.gtnewhorizons.angelica.glsm.GLSMDraws;

@SuppressWarnings("unused")
public class AngelicaDisk extends Disk {

    private static final float PI = (float) Math.PI;

    private void emitTexCoord(float x, float y) {
        if (super.textureFlag) {
            GLSMCurrentAttribs.glTexCoord2f(x, y);
        }
    }

    @Override
    public void draw(float innerRadius, float outerRadius, int slices, int loops) {
        float da;
        float dr;

        if (super.normals != GLU_NONE) {
            GLSMCurrentAttribs.glNormal3f(0.0f, 0.0f, super.orientation == GLU_OUTSIDE ? 1.0f : -1.0f);
        }

        da = 2.0f * PI / slices;
        dr = (outerRadius - innerRadius) / loops;

        switch (super.drawStyle) {
            case GLU_FILL -> {
                float dtc = 2.0f * outerRadius;
                float sa;
                float ca;
                float r1 = innerRadius;
                for (int l = 0; l < loops; l++) {
                    float r2 = r1 + dr;
                    GLSMDraws.glBegin(GL_TRIANGLE_STRIP);
                    if (super.orientation == GLU_OUTSIDE) {
                        for (int s = 0; s <= slices; s++) {
                            float a = s == slices ? 0.0f : s * da;
                            sa = sin(a);
                            ca = cos(a);
                            emitTexCoord(0.5f + sa * r2 / dtc, 0.5f + ca * r2 / dtc);
                            GLSMDraws.glVertex2f(r2 * sa, r2 * ca);
                            emitTexCoord(0.5f + sa * r1 / dtc, 0.5f + ca * r1 / dtc);
                            GLSMDraws.glVertex2f(r1 * sa, r1 * ca);
                        }
                    } else {
                        for (int s = slices; s >= 0; s--) {
                            float a = s == slices ? 0.0f : s * da;
                            sa = sin(a);
                            ca = cos(a);
                            emitTexCoord(0.5f - sa * r2 / dtc, 0.5f + ca * r2 / dtc);
                            GLSMDraws.glVertex2f(r2 * sa, r2 * ca);
                            emitTexCoord(0.5f - sa * r1 / dtc, 0.5f + ca * r1 / dtc);
                            GLSMDraws.glVertex2f(r1 * sa, r1 * ca);
                        }
                    }
                    GLSMDraws.glEnd();
                    r1 = r2;
                }
            }
            case GLU_LINE -> {
                for (int l = 0; l <= loops; l++) {
                    float r = innerRadius + l * dr;
                    GLSMDraws.glBegin(GL_LINE_LOOP);
                    for (int s = 0; s < slices; s++) {
                        float a = s * da;
                        GLSMDraws.glVertex2f(r * sin(a), r * cos(a));
                    }
                    GLSMDraws.glEnd();
                }
                for (int s = 0; s < slices; s++) {
                    float a = s * da;
                    float x = sin(a);
                    float y = cos(a);
                    GLSMDraws.glBegin(GL_LINE_STRIP);
                    for (int l = 0; l <= loops; l++) {
                        float r = innerRadius + l * dr;
                        GLSMDraws.glVertex2f(r * x, r * y);
                    }
                    GLSMDraws.glEnd();
                }
            }
            case GLU_POINT -> {
                GLSMDraws.glBegin(GL_POINTS);
                for (int s = 0; s < slices; s++) {
                    float a = s * da;
                    float x = sin(a);
                    float y = cos(a);
                    for (int l = 0; l <= loops; l++) {
                        float r = innerRadius * l * dr;
                        GLSMDraws.glVertex2f(r * x, r * y);
                    }
                }
                GLSMDraws.glEnd();
            }
            case GLU_SILHOUETTE -> {
                if (innerRadius != 0.0f) {
                    GLSMDraws.glBegin(GL_LINE_LOOP);
                    for (float a = 0.0f; a < 2.0f * PI; a += da) {
                        GLSMDraws.glVertex2f(innerRadius * sin(a), innerRadius * cos(a));
                    }
                    GLSMDraws.glEnd();
                }
                GLSMDraws.glBegin(GL_LINE_LOOP);
                for (float a = 0.0f; a < 2.0f * PI; a += da) {
                    GLSMDraws.glVertex2f(outerRadius * sin(a), outerRadius * cos(a));
                }
                GLSMDraws.glEnd();
            }
            default -> {
            }
        }
    }
}
