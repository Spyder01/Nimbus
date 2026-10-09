-- Where a deployed container can be opened from outside the cluster, set by the worker when the container is ready.
-- Null for containers that aren't public.
ALTER TABLE deployment_tasks
    ADD COLUMN url TEXT;
