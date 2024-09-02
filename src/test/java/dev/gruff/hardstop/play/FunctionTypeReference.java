package dev.gruff.hardstop.play;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.function.Function;

public abstract class FunctionTypeReference<F, T> {
    private final Type sourceType;
    private final Type targetType;
    private final Function<F, T> converter;

    protected FunctionTypeReference(Function<F, T> converter) {
        this.converter = converter;
        // Get the generic superclass (FunctionTypeReference<F, T>)
        Type superClass = getClass().getGenericSuperclass();
        if (superClass instanceof ParameterizedType) {
            // Get the actual type arguments (F and T)
            Type[] typeArguments = ((ParameterizedType) superClass).getActualTypeArguments();
            this.sourceType = typeArguments[0];
            this.targetType = typeArguments[1];
        } else {
            throw new RuntimeException("Unable to determine generic types for FunctionTypeReference");
        }
    }

    public Type getSourceType() {
        return this.sourceType;
    }

    public Type getTargetType() {
        return this.targetType;
    }

    public Function<F, T> getConverter() {
        return converter;
    }

    public static void main(String[] args) {
        // Example usage with Function<String, Integer>


        Function<String,String> parser=new Function<String, String>() {
            @Override
            public String apply(String integer) {
                return ""+integer;
            }
        };

        System.out.println(parser.getClass());
        
        FunctionTypeReference typeRef = new FunctionTypeReference<>(parser) {};

        System.out.println("Source type (F): " + typeRef.getSourceType());
        System.out.println("Target type (T): " + typeRef.getTargetType());

        // Example of using the converter function
      //  Function<String, Integer> converter = typeRef.getConverter();
      //  Integer length = converter.apply("Hello");
      //  System.out.println("Converted result: " + length);
    }
}
