-- Add an unknown external-schema FK. 0144 must fail without cascading deletion.
CREATE TABLE public.p3_unknown_fk (
    id bigint PRIMARY KEY, physical_model_id bigint NOT NULL
        REFERENCES "datahub-center".dh_modeling_physical_model(id)
);
INSERT INTO public.p3_unknown_fk VALUES (701,901);

