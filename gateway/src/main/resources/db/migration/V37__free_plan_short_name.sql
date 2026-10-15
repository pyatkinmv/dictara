-- V36 shortened the plan names so "<name> plan" reads well, but production's "free" plan had been edited by hand to
-- "Free Plan" (the migrations say "Free"), so free users still saw "Free Plan plan". Same fix, for that row.
UPDATE plans SET display_name = 'Free' WHERE name = 'free';
