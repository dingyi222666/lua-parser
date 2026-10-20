local Profile = {
  title = "Campaign Profile",
  flags = {
    enabled = true,
  },
}

function Profile:summary()
  return Profile.title
end

return Profile
