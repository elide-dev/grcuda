/*
 * Copyright (c) 2019, NVIDIA CORPORATION. All rights reserved.
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
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
 *  * Neither the name of NVIDIA CORPORATION nor the names of its
 *    contributors may be used to endorse or promote products derived
 *    from this software without specific prior written permission.
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
package com.nvidia.grcuda.runtime;

import com.nvidia.grcuda.GrCUDAException;
import com.nvidia.grcuda.MemberSet;
import com.nvidia.grcuda.NoneValue;
import com.nvidia.grcuda.runtime.stream.CUDAStream;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Cached.Shared;
import com.oracle.truffle.api.interop.ArityException;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.interop.UnsupportedTypeException;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import com.oracle.truffle.api.profiles.ValueProfile;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;


@ExportLibrary(InteropLibrary.class)
public class Device extends AbstractDevice implements TruffleObject {

    private static final String ID = "id";
    private static final String PROPERTIES = "properties";
    private static final String IS_CURRENT = "isCurrent";
    private static final String SET_CURRENT = "setCurrent";
    private static final MemberSet PUBLIC_MEMBERS = new MemberSet(ID, PROPERTIES, IS_CURRENT, SET_CURRENT);
    private final GPUDeviceProperties properties;
    private final CUDARuntime runtime;

    /**
     * List of streams associated to this device;
     */
    private final List<CUDAStream> streams;
    /**
     * Keep a set of the free available streams;
     */
    protected final Set<CUDAStream> freeStreams = new HashSet<>();

    public Device(int deviceId, CUDARuntime runtime) {
        super(deviceId);
        if (deviceId < 0) {
            throw new GrCUDAException("GPU device must have id >= 0, instead it is " + deviceId);
        }
        this.runtime = runtime;
        this.properties = new GPUDeviceProperties(deviceId, runtime);
        this.streams = new ArrayList<>();
    }

    /**
     * Return a stream (in no particular order) without any active computation on it;
     * @return a stream with no active computation on it
     */
    public CUDAStream getFreeStream(){
        // Get the first stream available, and remove it from the list of free streams;
        if (!freeStreams.isEmpty()) {
            CUDAStream stream = freeStreams.iterator().next();
            freeStreams.remove(stream);
            return stream;
        } else {
            throw new GrCUDAException("no free CUDA stream is available on device id=" + deviceId);
        }
    }

    /**
     * Create a new {@link CUDAStream} and add it to the list of streams associated to this device;
     */
    public CUDAStream createStream() {
        // To create a stream, we need to guarantee that this device is currently active;
        if (this.runtime.getCurrentGPU() != this.deviceId) {
            this.runtime.cudaSetDevice(this.deviceId);
        }
        // The stream is not added to the list of free streams:
        // a new stream is created only when it is required for a computation,
        // so it will be immediately "busy" anyway;
        CUDAStream newStream = this.runtime.cudaStreamCreate(this.streams.size());
        this.streams.add(newStream);
        return newStream;
    }

    /**
     * Set a specific CUDA stream as free, so it can be reused;
     * @param stream a free CUDA stream
     */
    public void updateFreeStreams(CUDAStream stream) {
        freeStreams.add(stream);
    }

    /**
     * Set all streams on this device as free, so they can be reused;
     */
    public void updateFreeStreams() {
        freeStreams.addAll(streams);
    }

    public int getNumberOfFreeStreams() {
        return freeStreams.size();
    }

    public int getNumberOfBusyStreams(){
        return this.streams.size() - freeStreams.size();
    }

    public GPUDeviceProperties getProperties() {
        return properties;
    }

    public int getDeviceId() {
        return deviceId;
    }

    /**
     * @return the list of streams associated to this device;
     */
    public List<CUDAStream> getStreams() {
        return streams;
    }

    /**
     * Cleanup and deallocate the streams associated to this device;
     */
    public void cleanup() {
        this.streams.forEach(runtime::cudaStreamDestroy);
        this.freeStreams.clear();
        this.streams.clear();
    }

    @Override
    public String toString() {
        return "GPU(id=" + deviceId + ")";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Device that = (Device) o;
        return deviceId == that.deviceId;
    }

    @Override
    public int hashCode() {
        return deviceId;
    }

    // Implementation of Truffle API

    @ExportMessage
    @SuppressWarnings("static-method")
    boolean hasMembers() {
        return true;
    }

    @ExportMessage
    @SuppressWarnings({"static-method", "unused"})
    Object getMembers(boolean includeInternal) {
        return PUBLIC_MEMBERS;
    }

    @ExportMessage
    @SuppressWarnings("static-method")
    boolean isMemberReadable(String memberName,
                    @Shared("memberName") @Cached("createIdentityProfile()") ValueProfile memberProfile) {
        String name = memberProfile.profile(memberName);
        return ID.equals(name) || PROPERTIES.equals(name) ||
                        IS_CURRENT.equals(name) || SET_CURRENT.equals(name);
    }

    @ExportMessage
    Object readMember(String memberName,
                    @Shared("memberName") @Cached("createIdentityProfile()") ValueProfile memberProfile) throws UnknownIdentifierException {
        if (!isMemberReadable(memberName, memberProfile)) {
            CompilerDirectives.transferToInterpreter();
            throw UnknownIdentifierException.create(memberName);
        }
        if (ID.equals(memberName)) {
            return deviceId;
        }
        if (PROPERTIES.equals(memberName)) {
            return properties;
        }
        if (IS_CURRENT.equals(memberName)) {
            return new IsCurrentFunction(deviceId, runtime);
        }
        if (SET_CURRENT.equals(memberName)) {
            return new SetCurrentFunction(deviceId, runtime);
        }
        CompilerDirectives.transferToInterpreter();
        throw UnknownIdentifierException.create(memberName);
    }

    @ExportMessage
    @SuppressWarnings("static-method")
    boolean isMemberInvocable(String memberName) {
        return IS_CURRENT.equals(memberName) || SET_CURRENT.equals(memberName);
    }

    @ExportMessage
    Object invokeMember(String memberName,
                    Object[] arguments,
                    @CachedLibrary("this") InteropLibrary interopRead,
                    @CachedLibrary(limit = "1") InteropLibrary interopExecute)
                    throws UnsupportedTypeException, ArityException, UnsupportedMessageException, UnknownIdentifierException {
        return interopExecute.execute(interopRead.readMember(this, memberName), arguments);
    }
}

/**
 * Find if the specified device is the one currently in use;
 */
@ExportLibrary(InteropLibrary.class)
final class IsCurrentFunction implements TruffleObject {
    private final int deviceId;
    private final CUDARuntime runtime;

    IsCurrentFunction(int deviceId, CUDARuntime runtime) {
        this.deviceId = deviceId;
        this.runtime = runtime;
    }

    @ExportMessage
    @SuppressWarnings("static-method")
    boolean isExecutable() {
        return true;
    }

    @SuppressWarnings("static-method")
    @ExportMessage
    public Object execute(Object[] arguments) throws ArityException {
        if (arguments.length != 0) {
            CompilerDirectives.transferToInterpreter();
            // FIXME: the maximum number of arguments is unbound (as each argument is a dimension of a N-dimensional tensor).
            //  Truffle currently uses -1 to handle an unbound number of arguments;
            throw ArityException.create(0, -1, arguments.length);
        }
        return runtime.getCurrentGPU() == deviceId;
    }
}

/**
 * Set the specified device as the one currently in use;
 */
@ExportLibrary(InteropLibrary.class)
class SetCurrentFunction implements TruffleObject {
    private final int deviceId;
    private final CUDARuntime runtime;

    SetCurrentFunction(int deviceId, CUDARuntime runtime) {
        this.deviceId = deviceId;
        this.runtime = runtime;
    }

    @ExportMessage
    @SuppressWarnings("static-method")
    boolean isExecutable() {
        return true;
    }

    @SuppressWarnings("static-method")
    @ExportMessage
    public Object execute(Object[] arguments) throws ArityException {
        if (arguments.length != 0) {
            CompilerDirectives.transferToInterpreter();
            throw ArityException.create(0, 0, arguments.length);
        }
        runtime.cudaSetDevice(deviceId);
        return NoneValue.get();
    }
}
