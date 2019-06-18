package ca.maple.swan.swift.tree;

import ca.maple.swan.swift.types.SwiftTypes;
import com.ibm.wala.cast.tree.CAstType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class SwiftFunctionType implements CAstType.Function {

    // TODO: Implement these methods for ScriptEntity and FunctionEntity. Might have to add a constructor.

    SwiftFunctionType(String returnType, ArrayList<String> argumentTypes) {
        this.cAstType = SwiftTypes.findOrCreateCAstType(returnType);
        for (String argumentType : argumentTypes) {
            argumentCAstTypes.add(SwiftTypes.findOrCreateCAstType(argumentType));
        }
    }

    private CAstType cAstType;
    private ArrayList<CAstType> argumentCAstTypes = new ArrayList<>();

    @Override
    public CAstType getReturnType() {
        return cAstType;
    }

    @Override
    public List<CAstType> getArgumentTypes() {
        return argumentCAstTypes;
    }

    @Override
    public Collection<CAstType> getExceptionTypes() {
        return null;
    }

    @Override
    public int getArgumentCount() {
        return argumentCAstTypes.size();
    }

    @Override
    public String getName() {
        return "CodeBody";
    }

    @Override
    public Collection<CAstType> getSupertypes() {
        return null;
    }
}