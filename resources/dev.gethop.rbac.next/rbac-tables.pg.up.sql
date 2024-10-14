BEGIN;
--;;
CREATE TABLE IF NOT EXISTS rbac_context_type (
    name VARCHAR(127) PRIMARY KEY,
    description TEXT);
--;;
CREATE TABLE IF NOT EXISTS rbac_context (
    id uuid PRIMARY KEY,
    context_type_name VARCHAR(127),
    resource_id UUID NOT NULL,
    CONSTRAINT rbac_context_context_type_name_fk FOREIGN KEY(context_type_name) REFERENCES rbac_context_type(name) ON UPDATE CASCADE,
    CONSTRAINT rbac_context_context_type_name_resource_id_uniq UNIQUE(context_type_name, resource_id));
--;;
CREATE INDEX IF NOT EXISTS rbac_context_resource_id_idx ON rbac_context(resource_id);
--;;
CREATE TABLE IF NOT EXISTS rbac_context_parent (
    child_id UUID NOT NULL,
    parent_id UUID NOT NULL,
    PRIMARY KEY (child_id, parent_id),
    CONSTRAINT rbac_context_parent_child_id_fk FOREIGN KEY(child_id) REFERENCES rbac_context(id) ON UPDATE CASCADE ON DELETE CASCADE,
    CONSTRAINT rbac_context_parent_parent_id_fk FOREIGN KEY(parent_id) REFERENCES rbac_context(id) ON UPDATE CASCADE ON DELETE CASCADE,
    CONSTRAINT rbac_context_parent_no_parent_of_self CHECK (child_id <> parent_id));
--;;
CREATE OR REPLACE FUNCTION rbac_context_parent_detect_cycle()
    RETURNS TRIGGER
    LANGUAGE plpgsql AS
$func$
DECLARE
    cycle_path RECORD;
BEGIN
    -- Because this function is used in a TRIGGER, it has access to a
    -- special table called `NEW`, which holds the new row to be
    -- inserted or updated when the trigger is executed.
    WITH RECURSIVE list_parents(child_id) AS
                   (
                    SELECT rcp.child_id
                    FROM rbac_context_parent AS rcp
                    WHERE rcp.parent_id = NEW.child_id
                    UNION ALL
                    SELECT rcp.child_id
                    FROM rbac_context_parent AS rcp, list_parents as lp
                    WHERE rcp.parent_id = lp.child_id
                   ) CYCLE child_id SET is_cycle USING path
    SELECT child_id, path INTO cycle_path
    FROM list_parents WHERE list_parents.child_id = NEW.parent_id LIMIT 1;
  IF cycle_path IS NOT NULL
  THEN
    RAISE EXCEPTION 'Cycle detected (%)', cycle_path;
  ELSE
    RETURN NEW;
  END IF;
END
$func$;
--;;
CREATE OR REPLACE TRIGGER rbac_context_parent_prevent_cycle
    BEFORE INSERT OR UPDATE ON rbac_context_parent
    FOR EACH ROW EXECUTE FUNCTION rbac_context_parent_detect_cycle();
--;;
CREATE TABLE IF NOT EXISTS rbac_role (
    id uuid PRIMARY KEY,
    name VARCHAR(127) NOT NULL,
    description TEXT,
    CONSTRAINT rbac_role_name_uniq UNIQUE(name));
--;;
CREATE TABLE IF NOT EXISTS rbac_role_assignment (
    role_id uuid NOT NULL,
    context_id uuid NOT NULL,
    user_id uuid NOT NULL,
    PRIMARY KEY (role_id, context_id, user_id),
    CONSTRAINT rbac_role_assignment_role_id_fk FOREIGN KEY(role_id) REFERENCES rbac_role(id) ON UPDATE CASCADE ON DELETE CASCADE,
    CONSTRAINT rbac_role_assignment_context_id_fk FOREIGN KEY(context_id) REFERENCES rbac_context(id) ON UPDATE CASCADE ON DELETE CASCADE);
--;;
CREATE INDEX IF NOT EXISTS rbac_role_assignment_context_id_idx ON rbac_role_assignment(context_id);
--;;
CREATE TABLE IF NOT EXISTS rbac_super_admin (
    user_id uuid PRIMARY KEY);
--;;
CREATE TABLE IF NOT EXISTS rbac_permission (
    id uuid PRIMARY KEY,
    name VARCHAR(127) NOT NULL,
    description TEXT,
    context_type_name VARCHAR(127) NOT NULL,
    CONSTRAINT rbac_permission_name_uniq UNIQUE(name),
    CONSTRAINT rbac_permission_context_type_name_fk FOREIGN KEY (context_type_name) REFERENCES rbac_context_type(name) ON UPDATE CASCADE);
--;;
CREATE TABLE IF NOT EXISTS rbac_role_permission (
    role_id uuid NOT NULL,
    permission_id uuid NOT NULL,
    permission_value SMALLINT NOT NULL,
    PRIMARY KEY (role_id, permission_id),
    CONSTRAINT rbac_role_permission_permission_id_fk FOREIGN KEY (permission_id) REFERENCES rbac_permission(id) ON UPDATE CASCADE,
    CONSTRAINT rbac_role_permission_role_id_fk FOREIGN KEY (role_id) REFERENCES rbac_role(id) ON UPDATE CASCADE ON DELETE CASCADE);
--;;
COMMIT;
