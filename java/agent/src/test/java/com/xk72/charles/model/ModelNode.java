package com.xk72.charles.model;

/** Test double. */
public abstract class ModelNode {
    private volatile ModelNode parent;

    public ModelNode getParent() {
        return parent;
    }

    public void setParentForTest(ModelNode p) {
        this.parent = p;
    }
}
