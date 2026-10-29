const uint MATERIAL_USE_MIP_OFFSET = 0u;
const uint MATERIAL_ALPHA_CUTOFF_OFFSET = 1u;

const float[4] ALPHA_CUTOFF = float[4](0.0, 0.1, 0.5, 1.0);

// Layers that do not use mipmaps are pushed four levels sharper, which lands them on level 0 for any
// on-screen geometry. That reproduces vanilla's per-layer behaviour now that every layer shares one terrain
// program, and it is the bias the rotated-grid path folds into its gradients.
float _material_mip_bias(uint material) {
    return ((material >> MATERIAL_USE_MIP_OFFSET) & 1u) != 0u ? 0.0 : -4.0;
}

float _material_alpha_cutoff(uint material) {
    return ALPHA_CUTOFF[(material >> MATERIAL_ALPHA_CUTOFF_OFFSET) & 3u];
}
