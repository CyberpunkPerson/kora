package ru.tinkoff.kora.database.annotation.processor.cassandra;

import com.squareup.javapoet.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.tinkoff.kora.annotation.processor.common.CommonClassNames;
import ru.tinkoff.kora.annotation.processor.common.CommonUtils;
import ru.tinkoff.kora.annotation.processor.common.Visitors;
import ru.tinkoff.kora.common.Tag;
import ru.tinkoff.kora.database.annotation.processor.DbUtils;
import ru.tinkoff.kora.database.annotation.processor.QueryWithParameters;
import ru.tinkoff.kora.database.annotation.processor.RepositoryGenerator;
import ru.tinkoff.kora.database.annotation.processor.model.QueryParameter;
import ru.tinkoff.kora.database.annotation.processor.model.QueryParameterParser;

import javax.annotation.Nullable;
import javax.annotation.processing.Filer;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static ru.tinkoff.kora.annotation.processor.common.MethodUtils.isVoid;

public class CassandraRepositoryGenerator implements RepositoryGenerator {
    private final TypeMirror repositoryInterface;
    private final Types types;
    private final Elements elements;
    private final Filer filer;

    public CassandraRepositoryGenerator(ProcessingEnvironment processingEnv) {
        var repository = processingEnv.getElementUtils().getTypeElement(CassandraTypes.REPOSITORY.canonicalName());
        if (repository == null) {
            this.repositoryInterface = null;
        } else {
            this.repositoryInterface = repository.asType();
        }
        this.types = processingEnv.getTypeUtils();
        this.elements = processingEnv.getElementUtils();
        this.filer = processingEnv.getFiler();
    }

    @Override
    @Nullable
    public TypeMirror repositoryInterface() {
        return this.repositoryInterface;
    }

    @Override
    public TypeSpec generate(TypeElement repositoryElement, TypeSpec.Builder type, MethodSpec.Builder constructor) {
        var repositoryType = (DeclaredType) repositoryElement.asType();
        var queryMethods = DbUtils.findQueryMethods(this.types, this.elements, repositoryElement);
        this.enrichWithExecutor(repositoryElement, type, constructor);
        for (var method : queryMethods) {
            var methodType = (ExecutableType) this.types.asMemberOf(repositoryType, method);
            var parameters = QueryParameterParser.parse(this.types, CassandraTypes.CONNECTION, method, methodType);
            var queryAnnotation = CommonUtils.findDirectAnnotation(method, DbUtils.QUERY_ANNOTATION);
            var queryString = CommonUtils.parseAnnotationValueWithoutDefault(queryAnnotation, "value").toString();
            var query = QueryWithParameters.parse(filer, queryString, parameters);
            this.parseResultMappers(method, parameters, methodType)
                .map(List::of)
                .ifPresent(resultMapper -> DbUtils.addMappers(this.types, type, constructor, resultMapper));
            DbUtils.addMappers(this.types, type, constructor, DbUtils.parseParameterMappers(
                method,
                parameters,
                query,
                tn -> CassandraNativeTypes.findNativeType(tn) != null,
                CassandraTypes.PARAMETER_COLUMN_MAPPER
            ));
            var methodSpec = this.generate(method, methodType, query, parameters);
            type.addMethod(methodSpec);
        }
        return type.addMethod(constructor.build()).build();
    }

    private MethodSpec generate(ExecutableElement method, ExecutableType methodType, QueryWithParameters query, List<QueryParameter> parameters) {
        var sql = query.rawQuery();
        for (var parameter : query.parameters().stream().sorted(Comparator.<QueryWithParameters.QueryParameter, Integer>comparing(p -> p.sqlParameterName().length()).reversed()).toList()) {
            sql = sql.replace(":" + parameter.sqlParameterName(), "?");
        }
        var b = DbUtils.queryMethodBuilder(method, methodType);
        b.addStatement(CodeBlock.of("var _query = new $T(\n  $S,\n  $S\n)", DbUtils.QUERY_CONTEXT, query.rawQuery(), sql));
        var batchParam = parameters.stream().filter(QueryParameter.BatchParameter.class::isInstance).findFirst().orElse(null);
        String profile = null;
        var profileAnnotation = CommonUtils.findAnnotation(elements, method, CassandraTypes.CASSANDRA_PROFILE);
        if (profileAnnotation != null) {
            profile = CommonUtils.parseAnnotationValue(elements, profileAnnotation, "value");
        }
        var returnType = methodType.getReturnType();
        var isFlux = CommonUtils.isFlux(returnType);
        var isMono = CommonUtils.isMono(returnType);
        if (isMono || isFlux) {
            b.addCode("return ");
            b.beginControlFlow("$T.deferContextual(_reactorCtx ->", isFlux ? Flux.class : Mono.class);
            b.addStatement("var _telemetry = this._connectionFactory.telemetry().createContext(ru.tinkoff.kora.common.Context.Reactor.current(_reactorCtx), _query)");
            b.addStatement("var _session = this._connectionFactory.currentSession()");
            b.addCode("return $T.fromCompletionStage(_session.prepareAsync(_query.sql()))", Mono.class);
            if (isMono) {
                b.beginControlFlow(".flatMap(_st ->");
            } else {
                b.beginControlFlow(".flatMapMany(_st ->");
            }
            b.addStatement("var _stmt = _st.boundStatementBuilder()");
        } else {
            b.addStatement("var _telemetry = this._connectionFactory.telemetry().createContext(ru.tinkoff.kora.common.Context.current(), _query)");
            b.addStatement("var _session = this._connectionFactory.currentSession()");
            b.addStatement("var _stmt = _session.prepare(_query.sql()).boundStatementBuilder()");
        }
        if (profile != null) {
            b.addStatement("_stmt.setExecutionProfileName($S)", profile);
        }

        StatementSetterGenerator.generate(b, method, query, parameters, batchParam);
        if (isMono || isFlux) {
            b.addStatement("var _rrs = _session.executeReactive(_s)");
            if (isVoid(((DeclaredType) returnType).getTypeArguments().get(0))) {
                b.addStatement("return $T.from(_rrs).then()", CommonClassNames.flux);
            } else {
                b.addStatement("return $N.apply(_rrs)", DbUtils.resultMapperName(method));
            }
            b.endControlFlow().addCode(")\n");// flatMap Statement
            b.addCode("""
                  .doOnEach(_s -> {
                    if (_s.isOnComplete()) {
                      _telemetry.close(null);
                    } else if (_s.isOnError()) {
                      _telemetry.close(_s.getThrowable());
                    }
                  });
                """);
            b.endControlFlow(")");// defer
        } else {
            b.beginControlFlow("try");
            b.addStatement("var _rs = _session.execute(_s)");
            if (returnType.getKind() != TypeKind.VOID) {
                b.addStatement("var _result = $N.apply(_rs)", DbUtils.resultMapperName(method));
            }
            b.addStatement("_telemetry.close(null)");
            if (returnType.getKind() != TypeKind.VOID) {
                b.addStatement("return _result");
            }
            b.nextControlFlow("catch (Exception _e)")
                .addStatement("_telemetry.close(_e)")
                .addStatement("throw _e")
                .endControlFlow();
        }
        return b.build();
    }


    private Optional<DbUtils.Mapper> parseResultMappers(ExecutableElement method, List<QueryParameter> parameters, ExecutableType methodType) {
        for (var parameter : parameters) {
            if (parameter instanceof QueryParameter.BatchParameter) {
                return Optional.empty();
            }
        }
        var returnType = methodType.getReturnType();
        var mapperName = DbUtils.resultMapperName(method);
        var mappings = CommonUtils.parseMapping(method);
        var resultSetMapper = mappings.getMapping(CassandraTypes.RESULT_SET_MAPPER);
        var reactiveResultSetMapper = mappings.getMapping(CassandraTypes.REACTIVE_RESULT_SET_MAPPER);
        var rowMapper = mappings.getMapping(CassandraTypes.ROW_MAPPER);
        if (CommonUtils.isFlux(returnType)) {
            var fluxParam = Visitors.visitDeclaredType(returnType, dt -> dt.getTypeArguments().get(0));
            if (isVoid(fluxParam)) {
                return Optional.empty();
            }
            var mapperType = ParameterizedTypeName.get(CassandraTypes.REACTIVE_RESULT_SET_MAPPER, TypeName.get(fluxParam), TypeName.get(returnType));
            if (reactiveResultSetMapper != null) {
                return Optional.of(new DbUtils.Mapper(reactiveResultSetMapper.mapperClass(), mapperType, mapperName));
            }
            if (rowMapper != null) {
                return Optional.of(new DbUtils.Mapper(rowMapper.mapperClass(), mapperType, mapperName, c -> CodeBlock.of("$T.flux($L)", CassandraTypes.REACTIVE_RESULT_SET_MAPPER, c)));
            }
            return Optional.of(new DbUtils.Mapper(mapperType, mapperName));
        }
        if (CommonUtils.isMono(returnType)) {
            var monoParam = Visitors.visitDeclaredType(returnType, dt -> dt.getTypeArguments().get(0));
            var mapperType = ParameterizedTypeName.get(CassandraTypes.REACTIVE_RESULT_SET_MAPPER, TypeName.get(monoParam), TypeName.get(returnType));
            if (isVoid(monoParam)) {
                return Optional.empty();
            }
            if (reactiveResultSetMapper != null) {
                return Optional.of(new DbUtils.Mapper(reactiveResultSetMapper.mapperClass(), mapperType, mapperName));
            }
            if (rowMapper != null) {
                if (CommonUtils.isList(monoParam)) {
                    return Optional.of(new DbUtils.Mapper(rowMapper.mapperClass(), mapperType, mapperName, c -> CodeBlock.of("$T.monoList($L)", CassandraTypes.REACTIVE_RESULT_SET_MAPPER, c)));
                } else {
                    return Optional.of(new DbUtils.Mapper(rowMapper.mapperClass(), mapperType, mapperName, c -> CodeBlock.of("$T.mono($L)", CassandraTypes.REACTIVE_RESULT_SET_MAPPER, c)));
                }
            }
            return Optional.of(new DbUtils.Mapper(mapperType, mapperName));
        }
        if (returnType.getKind() == TypeKind.VOID) {
            return Optional.empty();
        }
        var mapperType = ParameterizedTypeName.get(CassandraTypes.RESULT_SET_MAPPER, TypeName.get(returnType).box());
        if (resultSetMapper != null) {
            return Optional.of(new DbUtils.Mapper(resultSetMapper.mapperClass(), mapperType, mapperName));
        }
        if (rowMapper != null) {
            if (CommonUtils.isList(returnType)) {
                return Optional.of(new DbUtils.Mapper(rowMapper.mapperClass(), mapperType, mapperName, c -> CodeBlock.of("$T.listResultSetMapper($L)", CassandraTypes.RESULT_SET_MAPPER, c)));
            } else if (CommonUtils.isOptional(returnType)) {
                return Optional.of(new DbUtils.Mapper(rowMapper.mapperClass(), mapperType, mapperName, c -> CodeBlock.of("$T.optionalResultSetMapper($L)", CassandraTypes.RESULT_SET_MAPPER, c)));
            } else {
                return Optional.of(new DbUtils.Mapper(rowMapper.mapperClass(), mapperType, mapperName, c -> CodeBlock.of("$T.singleResultSetMapper($L)", CassandraTypes.RESULT_SET_MAPPER, c)));
            }
        }
        return Optional.of(new DbUtils.Mapper(mapperType, mapperName));
    }

    public void enrichWithExecutor(TypeElement repositoryElement, TypeSpec.Builder builder, MethodSpec.Builder constructorBuilder) {
        builder.addField(CassandraTypes.CONNECTION_FACTORY, "_connectionFactory", Modifier.PRIVATE, Modifier.FINAL);
        builder.addSuperinterface(CassandraTypes.REPOSITORY);
        builder.addMethod(MethodSpec.methodBuilder("getCassandraConnectionFactory")
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addAnnotation(Override.class)
            .returns(CassandraTypes.CONNECTION_FACTORY)
            .addCode("return this._connectionFactory;")
            .build());

        var executorTag = DbUtils.getTag(repositoryElement);
        if (executorTag != null) {
            constructorBuilder.addParameter(ParameterSpec.builder(CassandraTypes.CONNECTION_FACTORY, "_connectionFactory").addAnnotation(AnnotationSpec.builder(Tag.class).addMember("value", executorTag).build()).build());
        } else {
            constructorBuilder.addParameter(CassandraTypes.CONNECTION_FACTORY, "_connectionFactory");
        }
        constructorBuilder.addStatement("this._connectionFactory = _connectionFactory");
    }

}
