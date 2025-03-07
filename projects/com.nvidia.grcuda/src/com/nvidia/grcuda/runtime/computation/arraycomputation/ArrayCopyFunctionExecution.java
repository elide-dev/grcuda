/*
 * Copyright (c) 2020, 2021, NECSTLab, Politecnico di Milano. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *  * Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 *  * Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 *  * Neither the name of NECSTLab nor the names of its
 *    contributors may be used to endorse or promote products derived
 *    from this software without specific prior written permission.
 *  * Neither the name of Politecnico di Milano nor the names of its
 *    contributors may be used to endorse or promote products derived
 *    from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS ``AS IS'' AND ANY
 * EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY
 * OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.nvidia.grcuda.runtime.computation.arraycomputation;

import com.nvidia.grcuda.NoneValue;
import com.nvidia.grcuda.runtime.CPUDevice;
import com.nvidia.grcuda.runtime.array.AbstractArray;
import com.nvidia.grcuda.functions.DeviceArrayCopyFunction;
import com.nvidia.grcuda.runtime.computation.GrCUDAComputationalElement;
import com.nvidia.grcuda.runtime.stream.CUDAStream;
import com.oracle.truffle.api.CompilerDirectives;

import java.util.Optional;

/**
 * Computational elements that represents a low-level memory copy from/to a {@link AbstractArray}
 */
public abstract class ArrayCopyFunctionExecution extends GrCUDAComputationalElement {

    /**
     * The {@link AbstractArray} used in the copy;
     */
    protected final AbstractArray array;
    /**
     * Whether this computation copies data from the array or writes to it;
     */
    protected final DeviceArrayCopyFunction.CopyDirection direction;
    /**
     * Number of elements copied (expressed as number of elements, not as a size in bytes);
     */
    protected final long numElements;

    public static final boolean COMPUTATION_IS_DONE_BY_CPU = true;

    public ArrayCopyFunctionExecution(AbstractArray array, DeviceArrayCopyFunction.CopyDirection direction, long numElements, ArrayCopyFunctionExecutionInitializer dependencyInitializer) {
        super(array.getGrCUDAExecutionContext(), dependencyInitializer);
        this.array = array;
        this.direction = direction;
        this.numElements = numElements;
        this.isComputationDoneByCPU = COMPUTATION_IS_DONE_BY_CPU;
    }

    @Override
    public Object execute() {
        if (this.numElements * this.array.getElementType().getSizeBytes() > this.array.getSizeBytes()) {
            CompilerDirectives.transferToInterpreter();
            throw new IndexOutOfBoundsException();
        }
        this.executeInner();
        this.setComputationFinished();
        return NoneValue.get();
    }

    /**
     * Provide different implementations of the copy execution, depending on whether we operate on pointers, arrays, etc.
     */
    abstract void executeInner();

    @Override
    public void updateLocationOfArrays() {
        // FIXME: we should also consider the other array: if it is a DeviceArray its location is also updated;
        if (direction == DeviceArrayCopyFunction.CopyDirection.FROM_POINTER) {
            // We are copying new data to the array, so reset its status to updated on CPU;
            array.resetArrayUpToDateLocations(CPUDevice.CPU_DEVICE_ID);
        } else {
            // We are copying new data from the array (on the CPU) to somewhere else,
            // so the CPU must have updated data. It requires a sync if the context is not const-aware;
            if (array.getGrCUDAExecutionContext().isConstAware()) {
                array.addArrayUpToDateLocations(CPUDevice.CPU_DEVICE_ID);
            } else {
                // Clear the list of up-to-date locations: only the CPU has the updated array;
                array.resetArrayUpToDateLocations(CPUDevice.CPU_DEVICE_ID);
            }
        }
    }
    @Override
    protected Optional<CUDAStream> additionalStreamDependencyImpl() {
        return Optional.of(array.getStreamMapping());
    }

    @Override
    public String toString() {
        return "array copy on " + System.identityHashCode(this.array) + "; direction=" + this.direction + "; size=" + this.numElements;
    }
}

