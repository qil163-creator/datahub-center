-- PL/pgSQL bodies are not catalog-bound to referenced tables; 0144 postflight must detect this dependency.
CREATE FUNCTION "datahub-center".p3_unknown_function() RETURNS bigint LANGUAGE plpgsql AS
$function$
DECLARE result bigint;
BEGIN
    SELECT count(*) INTO result FROM "datahub-center".dh_modeling_physical_model;
    RETURN result;
END $function$;

