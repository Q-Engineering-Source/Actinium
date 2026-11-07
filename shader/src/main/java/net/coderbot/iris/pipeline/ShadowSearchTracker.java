package net.coderbot.iris.pipeline;

/**
 * Tracks the shadow terrain search the shadow pass submits each frame.
 *
 * <p>With asynchronous occlusion the lists drawn by a shadow pass are published by the join that runs at the
 * start of that pass, so they were produced by the search submitted during the previous frame. Remembering the
 * shadow angle of that submission lets the pass draw its lists at the angle they were searched with.
 */
final class ShadowSearchTracker {
    private boolean searchPending;
    private float searchedShadowAngle = Float.NaN;

    /**
     * Records the terrain searches the shadow pass submitted, using the submission counter sampled around the
     * renderer call that submits them.
     *
     * @param submissionsBefore counter value sampled before the call
     * @param submissionsAfter  counter value sampled after the call; only an increase means a search was submitted
     * @param shadowAngle       shadow angle the submitted search built its frustum from
     */
    void recordSubmissions(int submissionsBefore, int submissionsAfter, float shadowAngle) {
        if (submissionsAfter <= submissionsBefore) {
            return;
        }

        this.searchPending = true;
        this.searchedShadowAngle = shadowAngle;
    }

    /**
     * Consumes the pending search and returns the shadow angle the lists drawn this frame were searched with.
     *
     * @param currentShadowAngle shadow angle of the current frame
     * @return the searched angle when a search is pending, otherwise {@code currentShadowAngle}
     */
    float angleForFrame(float currentShadowAngle) {
        if (!this.searchPending) {
            return currentShadowAngle;
        }

        final float searchedAngle = this.searchedShadowAngle;
        this.searchPending = false;
        this.searchedShadowAngle = Float.NaN;
        return searchedAngle;
    }
}
