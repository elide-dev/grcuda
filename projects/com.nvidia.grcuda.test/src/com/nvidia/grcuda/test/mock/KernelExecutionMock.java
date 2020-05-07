package com.nvidia.grcuda.test.mock;

import com.nvidia.grcuda.NoneValue;
import com.nvidia.grcuda.gpu.computation.ComputationArgumentWithValue;
import com.nvidia.grcuda.gpu.computation.GrCUDAComputationalElement;
import com.nvidia.grcuda.gpu.executioncontext.GrCUDAExecutionContext;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Mock class to test the DAG execution;
 */
public class KernelExecutionMock extends GrCUDAComputationalElement {

    public KernelExecutionMock(GrCUDAExecutionContext grCUDAExecutionContext, List<ComputationArgumentWithValue> args) {
        super(grCUDAExecutionContext, args);
    }

    @Override
    public Object execute() { return NoneValue.get(); }

    @Override
    public boolean canUseStream() { return true; }

    @Override
    public void associateArraysToStreamImpl() { }

    @Override
    public String toString() {
        return "kernel mock" + "; args=[" +
                this.argumentList.stream().map(Object::toString).collect(Collectors.joining(", ")) +
                "]" + "; stream=" + this.getStream().getStreamNumber();
    }
}

