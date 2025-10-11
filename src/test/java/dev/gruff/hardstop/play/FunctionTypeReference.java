package dev.gruff.hardstop.play;

import dev.gruff.hardstop.api.HSClass;
import dev.gruff.hardstop.core.builder.ClassReader;
import dev.gruff.hardstop.core.internal.Utils;

import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.function.Function;

public abstract class FunctionTypeReference<F, T> {
    private final Type sourceType;
    private final Type targetType;
    private final Function<F, T> converter;

    public T convert(F f) {
        return converter.apply(f);
    }
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

    public static void main(String[] args) {
        // Example usage with Function<String, Integer>


        Function<String,String> parser=new Function<String, String>() {
            @Override
            public String apply(String integer) {
                return ""+integer;
            }
        };



        FunctionTypeReference typeRef = new FunctionTypeReference<>(parser) {};


        try {


            HSClass z= ClassReader.readClass(typeRef.getClass());
            System.out.println(z);
            System.out.println(z.type());
            System.out.println(z.isInnerClass());
            z.methods().stream().forEach(m -> {
                System.out.println("m "+m.name());
                System.out.println("m "+m.descriptor());
            });

            System.out.println("===");

            z= ClassReader.readClass(FunctionTypeReference.class);
            System.out.println("c n "+z.className());
            System.out.println("c t "+z.type());
            System.out.println("c i "+z.isInnerClass());
            z.methods().stream().forEach(m -> {

                System.out.println("m r "+m.reference());
                System.out.println("m a "+ Utils.methodAccessFlags(m.accessFlags()));

            });

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
