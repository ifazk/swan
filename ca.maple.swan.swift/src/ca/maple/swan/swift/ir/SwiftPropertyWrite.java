//===--- SwiftAnalysisPropertyWrite.java ---------------------------------===//
//
// This source file is part of the SWAN open source project
//
// Copyright (c) 2019 Maple @ University of Alberta
// All rights reserved. This program and the accompanying materials (unless
// otherwise specified by a license inside of the accompanying material)
// are made available under the terms of the Eclipse Public License v2.0
// which accompanies this distribution, and is available at
// http://www.eclipse.org/legal/epl-v20.html
//
//===---------------------------------------------------------------------===//

package ca.maple.swan.swift.ir;

import java.util.Collection;
import java.util.Collections;

import ca.maple.swan.swift.types.SwiftTypes;
import com.ibm.wala.cast.ir.ssa.AstPropertyWrite;
import com.ibm.wala.types.TypeReference;

public class SwiftPropertyWrite extends AstPropertyWrite {

    public SwiftPropertyWrite(int iindex, int objectRef, int memberRef, int value) {
        super(iindex, objectRef, memberRef, value);
    }

    /*
     * (non-Javadoc)
     * @see com.ibm.domo.ssa.Instruction#getExceptionTypes()
     */
    @Override
    public Collection<TypeReference> getExceptionTypes() {
        return Collections.singleton(SwiftTypes.Root);
    }

}