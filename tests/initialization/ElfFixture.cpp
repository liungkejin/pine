extern "C" {
int pine_fixture_value = 42;
int* pine_fixture_pointer = &pine_fixture_value;
int pine_fixture_function() { return *pine_fixture_pointer; }
}
