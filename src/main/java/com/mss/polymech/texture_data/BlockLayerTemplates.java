package com.mss.polymech.texture_data;

public enum BlockLayerTemplates {
    PIPE("pipes/template_pipe_core", "pipes/template_pipe_arm"),
    SMALL_PIPE("pipes/template_small_pipe_core", "pipes/template_small_pipe_arm"),
    BIG_PIPE("pipes/template_big_pipe_core", "pipes/template_big_pipe_arm"),
    HUGE_PIPE("pipes/template_huge_pipe_core", "pipes/template_huge_pipe_arm");

    private final String coreTemplatePath;
    private final String armTemplatePath;

    BlockLayerTemplates(String coreTemplatePath, String armTemplatePath) {
        this.coreTemplatePath = coreTemplatePath;
        this.armTemplatePath = armTemplatePath;
    }

    public String getCoreTemplatePath() { return coreTemplatePath; }
    public String getArmTemplatePath() { return armTemplatePath; }
}
