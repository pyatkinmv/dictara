-- The profile shows a plan as "<name> plan" and as a bare badge, so the names must read well both ways. Production
-- had "Ultra Plan" (edited by hand), which showed as "Ultra Plan plan", and "Try it" for guests ("Try it plan").
UPDATE plans SET display_name = 'Ultra' WHERE name = 'unlimited';
UPDATE plans SET display_name = 'Guest' WHERE name = 'anonymous';
