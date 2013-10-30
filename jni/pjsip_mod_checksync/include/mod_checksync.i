%header %{
#include "pjsip_mod_checksync.h"
%}

%feature("director") CheckSyncCallback;
%include pjsip_mod_checksync.h
