-- Speakers keep the case they were written in ("Maria Chen"), so the existing UNIQUE (user_id, name) lets
-- "Maria Chen" and "maria chen" coexist. This makes a name unique for a user whatever its case.
--
-- The speakers table is not written by any code before this release (the library is built in the same
-- release), so there is nothing that could collide. If it ever does, creating the index fails and the
-- migration stops loudly instead of picking a winner.
CREATE UNIQUE INDEX uq_speakers_user_lower_name ON speakers (user_id, lower(name));
