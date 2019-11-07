//===--- PrintInstruction.java -------------------------------------------===//
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

package ca.maple.swan.swift.translator.silir.instructions;

import ca.maple.swan.swift.translator.silir.context.InstructionContext;
import ca.maple.swan.swift.translator.silir.values.Value;

public class PrintInstruction extends SILIRInstruction {

    public final Value value;

    public PrintInstruction(String value, InstructionContext ic) {
        super(ic);
        this.value = ic.valueTable().getValue(value);
    }

    @Override
    public void visit(ISILIRVisitor v) {
        v.visitPrintInstruction(this);
    }

    @Override
    public String toString() {
        return "print " + value.simpleName() + "\n";
    }
}
